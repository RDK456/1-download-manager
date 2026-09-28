package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the update flow and the folder button, both of which had a button that
 * looked fine and did nothing.
 */
class UpdateAndRevealTest {

    // --- revealing a finished download ---------------------------------------

    /**
     * The row's folder button used to hand the file to `java.awt.Desktop.open`,
     * wrapped in runCatching, so on a machine where Desktop is unavailable it did
     * nothing and reported nothing. The icon is a folder, so the folder is what it
     * opens.
     */
    @Test
    fun theRowFolderButtonOpensTheFolderNotTheFile() {
        val app = File("src/main/kotlin/com/downloadhub/desktop/DesktopApp.kt").readText()
        assertTrue(
            "there is no reveal-in-folder helper",
            app.contains("fun revealInFolder(")
        )
        val body = app.substringAfter("fun revealInFolder(").substringBefore("\n}")
        assertTrue(
            "it must ask Explorer to show the file, which is Explorer's own switch:\n$body",
            body.contains("/select,")
        )
        assertFalse(
            "it must not hand the file to java.awt.Desktop, which is what silently " +
                "did nothing",
            body.contains("Desktop.open")
        )
    }

    @Test
    fun aFailedRevealIsReportedRatherThanSwallowed() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val body = controller.substringAfter("fun revealDownload(location: String?)").substringBefore("\n    }")
        assertTrue(
            "a failed reveal must say so:\n$body",
            body.contains("_messages.value")
        )
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the row button must go through the reporting action, not call the helper " +
                "and ignore the answer",
            screen.contains("actions.revealDownload(item.location)")
        )
    }

    /** The toolbar button and the row button must not look identical. */
    @Test
    fun theToolbarAndRowFolderButtonsAreDistinguishable() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the toolbar button should use the plain folder glyph",
            screen.contains("\"Downloads\", DlmIcons.Folder,")
        )
        assertTrue(
            "the row button should keep the open-folder glyph, with a tooltip that says " +
                "what it does",
            screen.contains("\"Show in folder\", onOpen")
        )
    }

    // --- the update flow -----------------------------------------------------

    /**
     * The real bug this guards: `launchInstaller` set the failure and then reset the
     * state to Idle on the very next line, so a download that could not be started
     * reported nothing and the dialog just closed.
     */
    @Test
    fun aFailedInstallerLaunchIsNotImmediatelyForgotten() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val body = controller.substringAfter("fun launchInstaller()").substringBefore("\n    }")
        val failAt = body.indexOf("isFailure")
        val resetAt = body.indexOf("_update.value = UpdateCheck.Idle")
        assertTrue(
            "the failure branch must come before the state is cleared, or the message " +
                "is discarded:\n$body",
            failAt >= 0 && resetAt > failAt
        )
        assertTrue(
            "the failure branch must return, or the clear runs anyway:\n$body",
            body.substring(failAt, resetAt).contains("return")
        )
    }

    @Test
    fun theDialogShowsTheReleaseNotes() {
        val update = File("src/main/kotlin/com/downloadhub/desktop/DesktopUpdate.kt").readText()
        assertTrue(
            "the release body is not captured, so there are no notes to show",
            update.contains("val body: String = \"\"")
        )
        val dialog = File("src/main/kotlin/com/downloadhub/desktop/UpdateDialog.kt").readText()
        assertTrue(
            "the dialog does not show the notes",
            dialog.contains("readableNotes")
        )
        assertTrue(
            "and does not label them",
            dialog.contains("What's new")
        )
    }

    /** A progress bar with no number cannot be compared against the file size. */
    @Test
    fun theDownloadProgressIsMeasurable() {
        val dialog = File("src/main/kotlin/com/downloadhub/desktop/UpdateDialog.kt").readText()
        assertTrue(
            "the dialog must show bytes fetched and total",
            dialog.contains("DisplayFormat.bytes(state.updateBytes)") &&
                dialog.contains("state.updateTotalBytes")
        )
    }

    /**
     * Windows Installer fails on some machines with "Could not set file security ...
     * Error: 5" and nothing in the app can fix that, so the zip has to be offered as
     * a real alternative rather than the user having to find it.
     */
    @Test
    fun thePortableZipIsOfferedWhenTheInstallerCannotRun() {
        val update = File("src/main/kotlin/com/downloadhub/desktop/DesktopUpdate.kt").readText()
        assertTrue(
            "no portable-zip asset lookup",
            update.contains("fun portableZip()")
        )
        val dialog = File("src/main/kotlin/com/downloadhub/desktop/UpdateDialog.kt").readText()
        assertTrue(
            "the dialog does not offer the zip",
            dialog.contains("Portable zip")
        )
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        assertTrue(
            "the download action cannot be asked for the zip",
            controller.contains("fun downloadUpdate(portable: Boolean = false)")
        )
    }

    /** A downloaded file the user cannot start should still be findable. */
    @Test
    fun aDownloadedUpdateCanBeFoundAfterwards() {
        val dialog = File("src/main/kotlin/com/downloadhub/desktop/UpdateDialog.kt").readText()
        assertTrue("the dialog offers no way to locate the download", dialog.contains("Show file"))
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        assertTrue(
            "the action behind it does not exist",
            controller.contains("fun revealDownloadedInstaller()")
        )
    }

    /**
     * A zip and an installer saved to the same folder must not both be called the
     * same thing, or the second download overwrites the first.
     */
    @Test
    fun aDownloadIsNamedForWhatItIs() {
        val update = File("src/main/kotlin/com/downloadhub/desktop/DesktopUpdate.kt").readText()
        val body = update.substringAfter("suspend fun download(").substringBefore("\n    ): Result<File>")
        assertTrue(
            "the download name is not parameterised:\n$body",
            body.contains("fileName: String =")
        )
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        assertTrue(
            "the asset name is not passed through",
            controller.contains("}, asset.name)")
        )
    }

    /**
     * The app has always reported "1.0.0" - in the title bar and, worse, to the
     * update check, which compares the newest release against that number and so has
     * always believed an update was available even on the latest build. The version
     * comes from the jar manifest, and nothing was writing one.
     */
    @Test
    fun theAppKnowsItsOwnVersion() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            "the jar manifest is never given an Implementation-Version, so the app " +
                "cannot read its own version and falls back to 1.0.0",
            Regex("Implementation-Version\" to appVersion").containsMatchIn(build)
        )
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        assertTrue(
            "the runtime read of the version is gone",
            controller.contains("implementationVersion")
        )
        assertFalse(
            "the 1.0.0 fallback is back, which is the number the app kept showing",
            Regex("implementationVersion[^}]*\\?:\\s*\"1\\.0\\.0\"").containsMatchIn(controller)
        )
    }

    /** The packaged jar is the only place that answer can come from. */
    @Test
    fun theBuiltJarCarriesTheVersion() {
        val jar = File("build/libs").listFiles { f -> f.name.startsWith("desktop-") && f.name.endsWith(".jar") }
            ?.firstOrNull()
        org.junit.Assume.assumeTrue("the module has not been jarred yet", jar != null)
        java.util.zip.ZipFile(jar!!).use { zip ->
            val manifest = zip.getEntry("META-INF/MANIFEST.MF")
            assertTrue("the jar has no manifest", manifest != null)
            val text = zip.getInputStream(manifest!!).bufferedReader().use { it.readText() }
            assertTrue(
                "the manifest has no Implementation-Version: $text",
                text.contains("Implementation-Version")
            )
            assertFalse(
                "it must not be 1.0.0: $text",
                Regex("Implementation-Version:\\s*1\\.0\\.0").containsMatchIn(text)
            )
        }
    }

    /** Notes are Markdown; a dialog is not a Markdown renderer. */
    @Test
    fun releaseNotesAreStrippedOfDecoration() {
        val update = File("src/main/kotlin/com/downloadhub/desktop/DesktopUpdate.kt").readText()
        val body = update.substringAfter("val readableNotes: String").substringBefore("\n}")
        listOf("removePrefix", "##", "- ").forEach { marker ->
            assertTrue("the notes are not cleaned up ($marker)", body.contains(marker))
        }
    }
}
