import java.net.URI
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.jetbrains.compose)
    // By id: the version comes from the root classpath, where the Android module
    // already pins it, so both builds compile with the same Compose compiler.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Shared with the Android APK so the two never drift apart.
val appVersion: String = (project.findProperty("appVersion") as String?) ?: "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // The libtorrent4j JVM artifact carries no native code, so :core stays
    // platform neutral and each app adds the native library it needs.
    implementation(libs.libtorrent4j)
    implementation("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")

    testImplementation(libs.junit)
}

val ytBinDir = layout.buildDirectory.dir("ytbin")

/**
 * Downloads a URL to a file.
 *
 * Uses curl.exe rather than java.net: GitHub redirects release assets to a
 * different host and HttpURLConnection times out on that redirect from some
 * networks, while curl's -L follows it. curl ships with Windows 10 1803+, and the
 * JVM is only a fallback.
 */
fun downloadTo(url: String, destination: File) {
    destination.parentFile.mkdirs()
    val partial = File(destination.parentFile, destination.name + ".part")
    partial.delete()
    val curl = File("C:/Windows/System32/curl.exe")
    val viaCurl = curl.isFile && runCatching {
        val process = ProcessBuilder(
            curl.absolutePath, "-sSL", "--fail", "--retry", "3",
            "--connect-timeout", "30", "--max-time", "900", "-o", partial.absolutePath, url
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

    if (!viaCurl) {
        logger.lifecycle("curl unavailable or failed; falling back to the JVM")
        partial.outputStream().use { sink ->
            URI(url).toURL().openStream().use { it.copyTo(sink) }
        }
    }
    // Only replace a previously good copy once the new one is fully written, so an
    // interrupted download cannot leave a truncated executable behind.
    require(partial.isFile && partial.length() > 0L) { "Download of $url produced nothing" }
    destination.delete()
    require(partial.renameTo(destination)) { "Could not move the download into place" }
}

/**
 * Downloads the standalone yt-dlp executable for Windows.
 *
 * yt-dlp is the only binary shipped inside the app. ffmpeg used to be bundled too,
 * at 100 MB, which made the download 150 MB across roughly a thousand files - and a
 * thousand files is exactly what a security agent fights with, which is how the
 * install died with "Failed to launch JVM". ffmpeg is now fetched on first use
 * instead, the same way the Android build already handles yt-dlp.
 */
val fetchYtDlp by tasks.registering {
    description = "Downloads the standalone yt-dlp executable for Windows."
    group = "build setup"
    val outputDir = ytBinDir
    val target = "yt-dlp.exe"
    outputs.file(outputDir.map { it.file(target) })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val out = File(dir, target)
        if (out.isFile && out.length() > 1_000_000L) return@doLast

        // The "latest/download" alias redirects unreliably, so the tag is resolved
        // through the API and the asset is fetched from a pinned URL.
        val api = URI("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
            .toURL()
            .openConnection()
            .apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "1-download-manager-build")
                connectTimeout = 20_000
                readTimeout = 20_000
            }
        val tag = api.getInputStream().bufferedReader().use { reader ->
            Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(reader.readText())?.groupValues?.get(1)
        }
        require(!tag.isNullOrBlank()) { "Could not resolve the latest yt-dlp release" }

        logger.lifecycle("Fetching yt-dlp $tag")
        downloadTo("https://github.com/yt-dlp/yt-dlp/releases/download/$tag/$target", out)
        require(out.length() > 1_000_000L) { "$target looks truncated (${out.length()} bytes)" }
        logger.lifecycle("yt-dlp $tag staged: ${out.length()} bytes")
    }
}

/**
 * Copies the bundled executables into the runtime resources so they land in the
 * packaged app's `lib` folder. Must match processResources' own output dir, or the
 * installer ships without them and YouTube silently fails.
 */
val stageYtBin by tasks.registering(Copy::class) {
    description = "Stages yt-dlp into the runtime resources."
    group = "build setup"
    dependsOn(fetchYtDlp)
    from(ytBinDir)
    into(layout.buildDirectory.dir("resources/main/lib"))
}

/**
 * Ships the browser extension as a runtime resource.
 *
 * Chrome and Edge will only load an extension from a folder the user picks, and a
 * store listing would mean publishing it somewhere first, so the app carries its
 * own copy and unpacks it on first run. It travels as a classpath resource for the
 * same reason the bundled yt-dlp does: no packaging hook, and it works from the
 * MSI, the portable zip and the IDE alike.
 */
val stageExtension by tasks.registering(Copy::class) {
    description = "Ships the browser extensions as runtime resources."
    group = "build setup"
    from(layout.projectDirectory.dir("browser-extension"))
    into(layout.buildDirectory.dir("resources/main/browser-extension"))
}

tasks.named("processResources") { dependsOn(stageYtBin, stageExtension) }

/**
 * Post-processes the unpacked app: drops the startup check beside the launcher and
 * removes the runtime stubs that make "Failed to launch JVM" so common.
 *
 * These are two small things but they share one reason to be a single task: both
 * modify the directory that `createDistributable` produces and rewrites on every run,
 * so both must happen after it and before anything is packaged. Keeping them together
 * means there is one task to order, rather than two that can drift apart.
 */
// Resolved to plain Files here because `layout` is not in scope on a bare Task
// receiver, and reaching for it inside doLast does not compile. The paths are known
// at configuration time; only their contents change between runs.
val packagedAppImage = file("build/compose/binaries/main/app/1DownloadManager")
val packagedRuntimeBin = File(packagedAppImage, "runtime/bin")
val troubleshootSource = file("dist-tools")

val prepareDistributable by tasks.registering {
    description = "Prepares the unpacked app for packaging: startup check, no API set stubs."
    group = "distribution"
    // By name, resolved when the task graph is built rather than at configuration
    // time: a Compose distribution task does not exist yet this early.
    dependsOn("createDistributable")
    // createDistributable rewrites the directory every time, so this can never be
    // considered done.
    outputs.upToDateWhen { false }
    doLast {
        val appImage: File = packagedAppImage
        if (!appImage.isDirectory) {
            error("No packaged app at $appImage; run createDistributable first.")
        }

        // --- the startup check ------------------------------------------------
        // "Failed to launch JVM" is all the jpackage launcher can say, whether the
        // cause is a half-extracted zip, a quarantined runtime DLL, or a network
        // drive. The app cannot report this itself - the failure happens before any
        // of its code runs - so the answer has to travel with the package.
        val shipped: List<File> = troubleshootSource
            .listFiles { f: File -> f.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (shipped.isEmpty()) error("No files in dist-tools; the package would ship without a startup check.")
        shipped.forEach { it.copyTo(File(appImage, it.name), overwrite = true) }
        logger.lifecycle("Shipped ${shipped.size} startup-check file(s) beside the launcher.")

        // --- a real java.exe ---------------------------------------------------
        // jlink strips the launchers, so the packaged app has no java.exe and the
        // jpackage stub is the only way into the JVM. When the stub cannot create
        // the JVM it can say nothing except "Failed to launch JVM", which is why the
        // error is so useless: it does not say which file was blocked or which path
        // was denied.
        //
        // java.exe is 50 KB and the rest of what it needs - jli.dll, the module
        // image, the other runtime libraries - is already in the image. Adding it
        // back gives the package a second way in that reports the actual error, and
        // that is what Start 1DownloadManager.bat uses. Verified by launching the
        // packaged app through it: same window, same behaviour.
        val runtimeBinDir = packagedRuntimeBin
        val javaExe = File(runtimeBinDir, "java.exe")
        if (!javaExe.isFile) {
            val candidates = listOf(
                File(System.getProperty("java.home") ?: "", "bin/java.exe"),
                File(System.getProperty("jdk.home") ?: "", "bin/java.exe")
            )
            val source = candidates.firstOrNull { it.isFile }
                ?: error(
                    "No java.exe to add to the package; looked in ${candidates.joinToString()}. " +
                        "Without it the package cannot report a real startup error."
                )
            source.copyTo(javaExe, overwrite = true)
            logger.lifecycle("Added java.exe (${javaExe.length()} bytes) so the package can report real startup errors.")
        }

        // --- the API set stubs ------------------------------------------------
        // These 45 files are the reason "Failed to launch JVM" was so hard to shake.
        // The packaged app has no java.exe at all: the launcher loads
        // runtime/bin/server/jvm.dll directly, and jvm.dll resolves its API set
        // imports through these local stub DLLs. Block or quarantine any one and the
        // JVM cannot initialise - the exact error, with no other symptom and nothing
        // in the app's own code to point at.
        //
        // They are also the most attractive thing in the package for a security
        // agent: 45 unsigned, near-empty DLLs named like operating-system
        // components. Cutting the download from 150 MB to 78 MB did not fix it,
        // because these files were never about size.
        //
        // On Windows 10 and later they are not needed - the loader resolves API sets
        // from the OS schema rather than by opening these files. Verified by running
        // the packaged app with all 45 removed: it starts normally.
        val runtimeBin: File = packagedRuntimeBin
        if (!runtimeBin.isDirectory) error("No packaged runtime at $runtimeBin.")

        val stubs: List<File> = runtimeBin.listFiles { f: File -> f.isFile && f.name.startsWith("api-ms-") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (stubs.isNotEmpty()) {
            // Anything this large is a real component, not a forwarder. If a future
            // JDK ships one that happens to be named api-ms-something, this stops the
            // build instead of shipping a JVM that cannot start.
            val suspicious = stubs.filter { it.length() > 512L * 1024L }
            if (suspicious.isNotEmpty()) {
                error(
                    "Refusing to strip, these are too large to be forwarder stubs: " +
                        suspicious.joinToString { "${it.name} (${it.length()} bytes)" }
                )
            }
            val bytes = stubs.sumOf { it.length() }
            val notRemoved = stubs.filterNot { it.delete() }
            if (notRemoved.isNotEmpty()) {
                error("Could not remove ${notRemoved.joinToString { it.name }}; the package is probably locked.")
            }
            logger.lifecycle("Stripped ${stubs.size} redundant API set stubs (${bytes / 1024} KB).")
        } else {
            logger.lifecycle("No API set stubs in the packaged runtime.")
        }

        // Removing a stub is safe; accidentally removing the JVM is not.
        val required = listOf("server/jvm.dll", "java.dll", "ucrtbase.dll", "vcruntime140.dll")
        val missing = required.filterNot { File(runtimeBin, it).isFile }
        if (missing.isNotEmpty()) {
            error("The runtime is missing ${missing.joinToString()}; the JVM cannot start without it.")
        }
    }
}


/**
 * Zips the unpacked app so it can be run without installing anything.
 *
 * Windows Installer failed on a machine whose security agent locks files the
 * installer is writing ("Could not set file security ... Error: 5"), and there is
 * no fix for that from inside the installer. Unzipping and running the exe needs no
 * elevation, no registry, and no Windows Installer at all, so it works where the MSI
 * cannot.
 */
val packageZip by tasks.registering(Zip::class) {
    description = "Builds a portable, install-free zip of the Windows app."
    group = "distribution"
    // By name, resolved when the task graph is built rather than at configuration
    // time: a Compose distribution task does not exist yet this early, and naming it
    // eagerly fails the whole build with "cannot reference task by name".
    dependsOn("createDistributable", "prepareDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/1DownloadManager"))
    // The folder is already named 1DownloadManager; wrapping it again produced
    // 1DownloadManager/1DownloadManager/.
    archiveFileName.set("1DownloadManager-$appVersion-portable.zip")
    isPreserveFileTimestamps = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

compose.desktop {
    application {
        mainClass = "com.downloadhub.desktop.MainKt"

        nativeDistributions {
            // Msi is the installer; the portable zip above is the fallback for
            // machines where an installer cannot run at all.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "1DownloadManager"
            packageVersion = appVersion
            description = "All-in-one download manager for HTTP, YouTube and torrents"
            vendor = "1 download manager"
            windows {
                menu = true
                perUserInstall = true
                // A stable upgrade code, so replacing an install keeps the entry in
                // Apps and features rather than leaving a second, stale copy.
                upgradeUuid = "6f2c9a54-2f1b-4a7d-9b3e-1c8d5e7a4f20"
            }
        }
    }
}
