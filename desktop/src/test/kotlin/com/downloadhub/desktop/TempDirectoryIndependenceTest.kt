package com.downloadhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the two ways this app used to depend on `%TEMP%`.
 *
 * A machine reported `Could not set file security for file 'D:\Config.Msi...'. Error: 5`
 * from Windows Installer, and that path is the giveaway: their TEMP resolves to `D:\`.
 * TEMP is not reliably a local, writable folder - it is routinely a network share or a
 * secondary volume, and where a security agent locks files down, writing there fails
 * in ways whose messages name nothing the user recognises.
 */
class TempDirectoryIndependenceTest {

    private fun source(module: String, file: String) =
        File("src/main/kotlin/com/downloadhub/$module/$file").readText()

    /**
     * The libtorrent native DLL is unpacked before it is loaded. Doing that in TEMP
     * means torrents fail on such a machine with a message that says the *library* is
     * missing, which points at the wrong thing entirely.
     */
    @Test
    fun theNativeLibraryIsNotUnpackedIntoTempByDefault() {
        val desktop = source("desktop", "DesktopTorrentEngine.kt")
        assertTrue(
            "the torrent engine must pass its own writable directory for the native " +
                "library, not fall back to %TEMP%",
            desktop.contains("nativeLibDir = { AppPaths.nativeLibDir }")
        )

        val core = File("../core/src/main/kotlin/com/downloadhub/core/LibtorrentNative.kt").readText()
        assertTrue(
            "the extraction directory must be settable, otherwise the desktop app " +
                "cannot avoid %TEMP%",
            core.contains("fun useDirectory(")
        )
    }

    /** The updater stages an MSI in TEMP, so an update cannot even be downloaded. */
    @Test
    fun theUpdaterDoesNotStageInTemp() {
        val update = source("desktop", "DesktopUpdate.kt")
        assertTrue(
            "the update installer must stage in the app's own cache folder",
            update.contains("UpdateInstaller(private val directory: File = AppPaths.updateDir)")
        )
        // Comments explain why TEMP is not used, so only code is checked here.
        val code = update.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertFalse(
            "the updater still reads java.io.tmpdir in code:\n$code",
            code.contains("java.io.tmpdir")
        )
    }

    /** Both must live under the profile, which is the one place known to be writable. */
    @Test
    fun theScratchFoldersAreUnderTheUsersProfile() {
        val store = source("desktop", "DesktopStore.kt")
        listOf("cacheDir", "nativeLibDir", "updateDir").forEach { name ->
            assertTrue("$name is missing from AppPaths", store.contains("val $name"))
        }
        // They have to be built from `home`, not from a system property that can point
        // somewhere unusable.
        val cacheBlock = store.substringAfter("val cacheDir").substringBefore("val updateDir")
        assertTrue(
            "the cache folder must be under AppPaths.home, not a system property:\n$cacheBlock",
            cacheBlock.contains("File(home,")
        )
    }

    /**
     * The package must contain a way to start that does not go through the jpackage
     * stub, because the stub can only ever say "Failed to launch JVM".
     */
    @Test
    fun thePackageCanReportARealStartupError() {
        val build = File("build.gradle.kts").readText()
        val task = build.substringAfter("val prepareDistributable").substringBefore("val packageZip")
        assertTrue(
            "the build must add java.exe to the package, otherwise there is no second " +
                "way in and no way to see the real error",
            task.contains("java.exe")
        )

        val bat = File("dist-tools/Start 1DownloadManager.bat")
        assertTrue("the fallback launcher is missing", bat.isFile)
        val text = bat.readText()
        assertTrue(
            "the fallback launcher must run the JVM directly",
            text.contains("runtime\\bin\\java.exe") && text.contains("com.downloadhub.desktop.MainKt")
        )
    }

    /**
     * Kotlin's trailing-lambda rule means adding a parameter to TorrentEngine silently
     * rebinds every `TorrentEngine { ... }` call to the new last parameter. That broke
     * the build once; the named arguments are the fix, and this is the guard.
     */
    @Test
    fun torrentEngineCallSitesUseNamedArguments() {
        listOf(
            File("src/main/kotlin/com/downloadhub/desktop/DesktopTorrentEngine.kt"),
            // The engine is built in AppContainer now, so the details sheet can reach it.
            File("../app/src/main/java/com/downloadhub/app/AppContainer.kt")
        ).filter { it.isFile }.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.name} constructs TorrentEngine with a trailing lambda, which now " +
                    "binds to nativeLibDir instead of torrentRoot",
                Regex("TorrentEngine\\s*\\{").containsMatchIn(text)
            )
            assertTrue(
                "${file.name} must pass torrentRoot by name, on the same or a following line",
                Regex("TorrentEngine\\s*\\(\\s*\\n?\\s*torrentRoot\\s*=").containsMatchIn(text)
            )
        }
    }

    /** The diagnostic has to keep the strings that make its advice specific. */
    @Test
    fun theDiagnosticAsksTheJvmForTheRealError() {
        val text = File("dist-tools/Troubleshoot.ps1").readText()
        listOf(
            "Asking the JVM directly",       // the section that gets the real message
            "Start 1DownloadManager.bat",    // what to do when the stub is the problem
            "security software is blocking", // the actual cause, named
            "jvm.dll",
            "java.exe"
        ).forEach { needle ->
            assertTrue("the diagnostic no longer mentions '$needle'", text.contains(needle))
        }
    }
}
