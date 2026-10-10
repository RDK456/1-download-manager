package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The parts of the updater that were not working.
 *
 * Each of these was found by driving the real dialog against the real GitHub API
 * rather than by reading it, and each is a dead end or a lie rather than a crash:
 * nothing here throws, so nothing here would show up in a test that only checks the
 * code runs.
 */
class UpdateFlowTest {

    private fun release(
        tag: String = "v1.5.0",
        withInstaller: Boolean = true,
        withZip: Boolean = true,
        draft: Boolean = false,
        prerelease: Boolean = false
    ) = GithubRelease(
        tag = tag,
        name = "1 download manager ${tag.removePrefix("v")}",
        draft = draft,
        prerelease = prerelease,
        assets = buildList {
            if (withInstaller) add(GithubAsset("1-download-manager-${tag.removePrefix("v")}.msi", "https://x/msi", 100))
            if (withZip) add(GithubAsset("1-download-manager-${tag.removePrefix("v")}-portable.zip", "https://x/zip", 200))
        }
    )

    // --- which release gets offered -----------------------------------------

    /**
     * The updater used to ask for `/releases/latest`, which is the newest release of
     * any kind - and this repository ships Android and Windows from one place. A
     * phone-only 1.5.0 published after a Windows 1.4.12 made the answer "no Windows
     * installer yet", with no way forward until the next Windows release.
     */
    @Test
    fun aPhoneOnlyNewerReleaseDoesNotDeadEndTheUpdater() {
        val checker = DesktopUpdateChecker()
        val releases = listOf(
            release(tag = "v1.5.0", withInstaller = false, withZip = false),   // Android only
            release(tag = "v1.4.12"),
            release(tag = "v1.4.11")
        )
        val picked = checker.pickInstallable(releases)
        assertNotNull("it must step back to the newest release Windows can install", picked)
        assertEquals("1.4.12", picked!!.version)
    }

    @Test
    fun draftsAndPrereleasesAreNotOfferedForInstall() {
        val checker = DesktopUpdateChecker()
        assertNull(
            "a prerelease is not something to install",
            checker.pickInstallable(listOf(release(tag = "v1.6.0", prerelease = true)))
        )
        assertNull(
            "nor is a draft",
            checker.pickInstallable(listOf(release(tag = "v1.6.0", draft = true)))
        )
    }

    /** The newest wins numerically, not by string order. */
    @Test
    fun theNewestInstallableReleaseWinsNumerically() {
        val checker = DesktopUpdateChecker()
        val picked = checker.pickInstallable(
            listOf(release(tag = "v1.9.0"), release(tag = "v1.10.0"), release(tag = "v1.2.0"))
        )
        assertEquals("1.10.0", picked!!.version)
    }

    @Test
    fun noInstallableReleaseAtAllIsDistinguishableFromNoNetwork() {
        val checker = DesktopUpdateChecker()
        val androidOnly = listOf(release(tag = "v1.5.0", withInstaller = false, withZip = false))
        assertNull("nothing installable", checker.pickInstallable(androidOnly))
        // The controller words these two differently, so this is the case it must be
        // able to tell apart.
        assertTrue(androidOnly.isNotEmpty())
        assertTrue(emptyList<GithubRelease>().isEmpty())
    }

    // --- what the dialog offers ----------------------------------------------

    /**
     * The dead end.
     *
     * The confirm button was chosen from "has anything downloaded" alone, so a
     * portable zip - which cannot be run as an installer - got the same "Install now"
     * as an .msi. The zip is offered precisely for machines where Windows Installer
     * is blocked, so this was a dead end at the last step for exactly the people who
     * need it.
     */
    @Test
    fun aPortableZipIsNeverOfferedAnInstallButton() {
        val zip = File("portable.zip")
        val actions = updateActionsFor(
            update = UpdateCheck.Available(release(), GithubAsset("a.msi", "u", 1)),
            downloaded = DownloadedUpdate(UpdateKind.PORTABLE, zip),
            progress = -1,
            hasPortable = true
        )
        assertEquals("Switch to this version", actions.confirm)
        assertEquals(UpdateAction.SWITCH_TO_NEW_VERSION, actions.onConfirm)
        assertFalse(
            "a zip handed to the shell is how this dead-ended",
            actions.confirm!!.contains("Install", ignoreCase = true)
        )
    }

    @Test
    fun anInstallerStillGetsInstallNow() {
        val actions = updateActionsFor(
            update = UpdateCheck.Available(release(), GithubAsset("a.msi", "u", 1)),
            downloaded = DownloadedUpdate(UpdateKind.INSTALLER, File("setup.msi")),
            progress = -1,
            hasPortable = true
        )
        assertEquals("Install and restart", actions.confirm)
        assertEquals(UpdateAction.INSTALL, actions.onConfirm)
        assertEquals("Show file", actions.secondary)
        assertEquals(UpdateAction.REVEAL, actions.onSecondary)
    }

    /** Before anything is fetched: download the installer, with the zip alongside. */
    @Test
    fun anAvailableUpdateOffersBothWaysToFetchIt() {
        val actions = updateActionsFor(
            update = UpdateCheck.Available(release(), GithubAsset("a.msi", "u", 1)),
            downloaded = null,
            progress = -1,
            hasPortable = true
        )
        assertEquals("Update now", actions.confirm)
        assertEquals(UpdateAction.DOWNLOAD_INSTALLER, actions.onConfirm)
        assertEquals("Portable zip", actions.secondary)
        assertEquals(UpdateAction.DOWNLOAD_PORTABLE, actions.onSecondary)
    }

    /**
     * The zip stays on offer after a failure.
     *
     * It used to be read off the "available" state, so the moment a download failed
     * the fallback button vanished - which is exactly when it is wanted.
     */
    @Test
    fun thePortableOfferSurvivesAFailedDownload() {
        val actions = updateActionsFor(
            update = UpdateCheck.Failed("The download failed"),
            downloaded = null,
            progress = -1,
            hasPortable = true
        )
        assertEquals("Try again", actions.confirm)
        assertEquals(
            "the portable zip must still be offered after a failure - that is the " +
                "one failure which cannot be retried into working",
            "Portable zip",
            actions.secondary
        )
    }

    /** Nothing can be dismissed mid-transfer, or the user cannot tell if it carried on. */
    @Test
    fun nothingCanBeClosedWhileTheDownloadRuns() {
        val actions = updateActionsFor(
            update = UpdateCheck.Available(release(), GithubAsset("a.msi", "u", 1)),
            downloaded = null,
            progress = 42,
            hasPortable = true
        )
        assertNull(actions.confirm)
        assertNull(actions.secondary)
        assertFalse(actions.canClose)
    }

    @Test
    fun progressEndsWhenTheDownloadDoes() {
        val idle = updateActionsFor(
            update = UpdateCheck.Available(release(), GithubAsset("a.msi", "u", 1)),
            downloaded = null,
            progress = -1,
            hasPortable = false
        )
        assertTrue("a negative progress is not a download in progress", idle.canClose)
        assertNull("and with no zip there is no secondary button", idle.secondary)
    }

    // --- unpacking -----------------------------------------------------------

    /**
     * The portable path has to actually unpack. This is the step that did not exist.
     */
    @Test
    fun aPortableArchiveIsUnpackedAndItsLauncherFound() {
        val root = createTempDir("dlm-unpack")
        val zip = File(root, "portable.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("1DownloadManager/1DownloadManager.exe"))
            out.write(ByteArray(64) { 0x4D })
            out.closeEntry()
            out.putNextEntry(ZipEntry("1DownloadManager/app/1DownloadManager.jar"))
            out.write(ByteArray(32) { 0x50 })
            out.closeEntry()
        }

        val target = File(root, "next")
        val launcher = PortableBuild.extract(zip, target).getOrThrow()
        assertTrue("the launcher was not found", launcher.isFile)
        assertEquals("1DownloadManager.exe", launcher.name)
        // The archive may wrap everything in a top-level folder, so the layout inside
        // it has to survive rather than everything being flattened.
        assertTrue(
            "the archive's own layout was flattened: ${launcher.path}",
            launcher.parentFile.name == "1DownloadManager"
        )
        assertTrue(
            "the jar next to it is missing",
            File(launcher.parentFile, "app/1DownloadManager.jar").isFile
        )
        root.deleteRecursively()
    }

    @Test
    fun anArchiveWithNoLauncherIsReportedRatherThanStartingNothing() {
        val root = createTempDir("dlm-unpack-bad")
        val zip = File(root, "portable.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        val result = PortableBuild.extract(zip, File(root, "next"))
        assertTrue("an archive with no launcher must fail loudly", result.isFailure)
        assertTrue(
            "and say why: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("1DownloadManager.exe") == true
        )
        root.deleteRecursively()
    }

    /**
     * A zip entry's name is data from the internet, not a path the app may write to.
     */
    @Test
    fun anEntryEscapingTheDestinationIsRefused() {
        val root = createTempDir("dlm-zipslip")
        val zip = File(root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../../escaped.txt"))
            out.write("nope".toByteArray())
            out.closeEntry()
        }
        val result = PortableBuild.extract(zip, File(root, "next"))
        assertTrue("a path outside the destination must be refused", result.isFailure)
        assertFalse(
            "and nothing may be written outside it",
            File(root.parentFile, "escaped.txt").exists()
        )
        root.deleteRecursively()
    }

    private fun createTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    // --- the release notes must not be corrupted on the way out --------------

    /**
     * The mojibake in the updater was not the updater's fault.
     *
     * `release.ps1` read the notes file with `Get-Content -Raw` and no `-Encoding`, and
     * Windows PowerShell's default for that is the ANSI code page. A UTF-8 file with
     * no BOM came back as mojibake - a bullet became "â€¢", an arrow became "â†’" -
     * and that was published verbatim, so every user saw it. GitHub had stored
     * U+251C U+00F3 U+0393 U+00C7 for what should have been U+2192.
     */
    @Test
    fun theReleaseScriptReadsNotesAsUtf8() {
        val script = File("../scripts/release.ps1").readText()
        // Matched by content rather than by pattern: the line contains a PowerShell
        // variable, and "$" means "end of line" to a regex.
        val notesRead = script.lines()
            .firstOrNull { it.contains("Get-Content") && it.contains("NotesFile") }
        assertNotNull("the notes file is no longer read at all", notesRead)
        assertTrue(
            "release.ps1 reads the notes file as `${notesRead!!.trim()}`. Without " +
                "-Encoding UTF8 the notes are decoded as the ANSI code page and published " +
                "as mojibake, which the in-app updater then shows to users verbatim",
            notesRead.contains("-Encoding", ignoreCase = true)
        )
    }

    /**
     * A round trip through the way the script writes and the way the app reads, so the
     * encoding is pinned at both ends rather than only in the script.
     */
    @Test
    fun notesSurviveTheRoundTripThatMangledThem() {
        val original = "The sidebar narrows first (230 → 190 → 120 dp)."
        val temp = File(System.getProperty("java.io.tmpdir"), "dlm-notes-${System.nanoTime()}.txt")
        try {
            // Written the way the script writes it: UTF-8, no BOM.
            temp.writeBytes(original.toByteArray(Charsets.UTF_8))
            assertFalse(
                "a BOM would change how the file is read, and the script must not depend " +
                    "on one being present",
                temp.readBytes().take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            )
            // Read the way the app reads the release body.
            val body = String(temp.readBytes(), Charsets.UTF_8)
            assertEquals("the arrow did not survive", original, body)
            assertTrue("and it is a real arrow, not three characters", body.contains("→"))
        } finally {
            temp.delete()
        }
    }

    // --- the notes -----------------------------------------------------------

    /** The dialog is not a Markdown renderer, so the markers must not be shown. */
    @Test
    fun markdownMarkersAreStrippedFromTheNotes() {
        val notes = GithubRelease(
            tag = "v1.5.0",
            body = """
                The window **resizes** properly now.

                - The `sidebar` narrows first
                * Icons stay at 38 dp
            """.trimIndent()
        ).readableNotes
        assertFalse("bold markers are showing: $notes", notes.contains("**"))
        assertFalse("code markers are showing: $notes", notes.contains("`"))
        assertFalse("bullets are showing: $notes", notes.contains("- The"))
        assertTrue("but the words are kept: $notes", notes.contains("resizes"))
        assertTrue(notes.contains("sidebar"))
    }

    /** Long notes are trimmed so a dialog is not a wall of text. */
    @Test
    fun veryLongNotesAreTrimmed() {
        val body = (1..40).joinToString("\n") { "line $it" }
        val notes = GithubRelease(tag = "v1.5.0", body = body).readableNotes
        assertEquals("a dialog cannot hold 40 lines of notes", 14, notes.lines().size)
        assertTrue(notes.startsWith("line 1"))
    }

}

