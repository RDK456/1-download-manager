package com.downloadhub.core

import java.io.File

/**
 * Makes libtorrent's native library loadable on a plain JVM.
 *
 * On Android the native `.so` is unpacked from the APK into a directory the OS
 * loader searches, so libtorrent4j's own `System.loadLibrary` succeeds. On Windows
 * the DLL ships inside a jar at `lib/<arch>/libtorrent4j.dll`, nothing extracts it,
 * and that `loadLibrary` throws - which surfaces as a bare
 * `LinkageError: Look for your architecture binary instructions`, a message that
 * gives no hint about the real cause.
 *
 * libtorrent4j supports a `libtorrent4j.jni.path` system property: when it is set,
 * the library is loaded from that absolute path instead of via `loadLibrary`. This
 * extracts the DLL to a temp file and points the property at it, which is the
 * library's own supported mechanism rather than a workaround.
 *
 * Must run before any libtorrent4j class is touched, because the first such
 * reference triggers the JNI initialiser that performs the load.
 */
internal object LibtorrentNative {

    /** The property libtorrent4j reads in its static initialiser. */
    private const val JNI_PATH_PROPERTY = "libtorrent4j.jni.path"

    @Volatile
    private var prepared = false

    /**
     * Where the native library is unpacked to.
     *
     * This used to be `java.io.tmpdir`, which is a trap: on a machine whose TEMP
     * points at a network share, a locked-down volume, or a drive with a security
     * filter driver on it, the extraction fails and torrents stop working for a
     * reason that appears nowhere in the message. The apps pass their own writable
     * directory under the user's profile instead; this default only applies when
     * nothing was set.
     */
    @Volatile
    private var extractionDir: File? = null

    /** Points the extraction at a directory the caller knows is writable. */
    fun useDirectory(directory: File) {
        extractionDir = directory
    }

    /** True when the native library was found and pointed at. */
    val available: Boolean
        get() = runCatching { ensureReady() }.isSuccess

    @Synchronized
    fun ensureReady() {
        if (prepared) return
        // Android already finds its .so, and a second extraction would be pointless.
        if (isAndroid()) {
            prepared = true
            return
        }
        val extracted = extract()
        check(extracted != null) {
            "libtorrent4j native library not found: expected " +
                "lib/${architecture()}/libtorrent4j.$libraryExtension on the classpath"
        }
        System.setProperty(JNI_PATH_PROPERTY, extracted.absolutePath)
        prepared = true
    }

    private fun extract(): File? {
        val resource = "lib/${architecture()}/libtorrent4j.$libraryExtension"
        // ClassLoader.getResource takes the name *without* a leading slash; passing
        // one silently returns null, which is why the DLL was never found.
        val stream = LibtorrentNative::class.java.classLoader?.getResourceAsStream(resource)
            ?: LibtorrentNative::class.java.getResourceAsStream("/$resource")
            ?: return null
        val directory = (extractionDir ?: File(System.getProperty("java.io.tmpdir"), ""))
            .let { if (it.name.isEmpty()) File(it, "libtorrent4j-native") else it }
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, "libtorrent4j.$libraryExtension")
        // Reuse a previous extraction; rewriting it on every launch is pointless
        // and would break an already-loaded library on Windows.
        if (target.isFile && target.length() > 1_000_000L) return target
        return try {
            stream.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target.takeIf { it.length() > 1_000_000L }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detects Android without a compile-time reference to the SDK.
     *
     * `:core` is a plain JVM module, so naming `android.os.Build` would not
     * compile; the runtime class is simply absent on a desktop JVM and present on
     * Android, which is enough to tell them apart.
     */
    private fun isAndroid(): Boolean = runCatching {
        Class.forName("android.os.Build")
        true
    }.getOrDefault(false)

    private fun architecture(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val bits = if (arch.contains("64")) "x86_64" else "x86"
        return if (os.contains("mac") || os.contains("darwin")) "x86_64" else bits
    }

    private val libraryExtension: String
        get() = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "dll" else "so"
}
