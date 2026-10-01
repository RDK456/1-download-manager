package com.downloadhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers fetching ffmpeg from the archive, which is now the fallback rather than the plan.
 *
 * ffmpeg ships in the package as a single executable - see [FirstRunSetupTest] for why
 * one file and not the archive's thousand - so on a normal install this code never runs.
 * It stays because it is what happens when the shipped copy is missing: an older install
 * being updated, an antivirus that quarantined it, a tools folder the user cleaned out.
 *
 * The archive handling is the part worth guarding. It has to find the executable under a
 * version-named folder, refuse a decoy of the same name in the documentation directory,
 * refuse a download cut short, and refuse an archive with no executable in it at all -
 * because each of those failures otherwise shows up as an HTML file called ffmpeg.exe, or
 * a truncated one, or a job that dies part way through with nothing to explain why.
 */
class FfmpegOnDemandTest {

    private fun tempDir(): File =
        File.createTempFile("dlm-ffmpeg-test", "").let {
            it.delete()
            File(it, "tools").apply {
                parentFile.mkdirs()
                // installDir is created by the app, not the archive writer.
                mkdirs()
            }
        }

    /**
     * Builds an archive with the same shape as gyan.dev's, verified against the real
     * one: 49 entries, the executable under a version-named `bin/` folder, and the
     * same decoys around it.
     */
    private fun archive(dir: File, exeBytes: Int): File {
        val zip = File(dir, "ffmpeg.zip")
        val filler = ByteArray(exeBytes) { 'x'.code.toByte() }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("ffmpeg-9.0.2-essentials_build/doc/ffmpeg.exe"))
            out.write("<html>not the executable</html>".toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe"))
            out.write(filler)
            out.closeEntry()

            out.putNextEntry(ZipEntry("ffmpeg-9.0.2-essentials_build/bin/"))
            out.closeEntry()

            out.putNextEntry(ZipEntry("ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe"))
            out.write(filler)
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun theExecutableIsFoundAndPlacedEvenThoughTheFolderIsVersionNamed() {
        val dir = tempDir()
        try {
            val tools = YtDlpTools(dir)
            assertTrue("the real archive layout was not unpacked", tools.unpackFfmpeg(archive(dir, 1_200_000)))
            assertTrue("ffmpeg.exe was not created", tools.ffmpeg.isFile)
            assertTrue("ffmpeg.exe is the wrong size: ${tools.ffmpeg.length()}", tools.ffmpeg.length() >= 1_000_000L)
            assertTrue("ffmpegReady should now be true", tools.ffmpegReady)
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    /**
     * The archive contains a decoy `doc/ffmpeg.exe`. Picking that one would leave an
     * HTML file named ffmpeg.exe, which fails in a much more confusing way.
     */
    @Test
    fun theDecoyInTheDocumentationFolderIsNotPromoted() {
        val dir = tempDir()
        try {
            val tools = YtDlpTools(dir)
            tools.unpackFfmpeg(archive(dir, 1_200_000))
            assertFalse(
                "the documentation HTML was written out as the executable",
                tools.ffmpeg.readText().startsWith("<html>")
            )
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    /**
     * A download cut short must not be promoted: the exe would exist and look fine,
     * then fail part-way through a job the user is waiting on.
     */
    @Test
    fun aTruncatedExecutableIsNotPromoted() {
        val dir = tempDir()
        try {
            val tools = YtDlpTools(dir)
            assertFalse("a truncated exe was accepted", tools.unpackFfmpeg(archive(dir, 500)))
            assertFalse("a truncated exe was left behind", tools.ffmpeg.exists())
            assertFalse("the partial file was left behind", File(dir, "ffmpeg.exe.part").exists())
            assertFalse("ffmpegReady must stay false", tools.ffmpegReady)
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun anArchiveWithoutTheExecutableIsRejected() {
        val dir = tempDir()
        try {
            val zip = File(dir, "empty.zip")
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("readme.txt")) }
            val tools = YtDlpTools(dir)
            assertFalse("an archive with no executable was accepted", tools.unpackFfmpeg(zip))
            assertFalse("ffmpegReady must stay false", tools.ffmpegReady)
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    /** Re-running must be a no-op, not a second 100 MB download. */
    @Test
    fun anAlreadyInstalledFfmpegShortCircuits() {
        val dir = tempDir()
        try {
            val tools = YtDlpTools(dir)
            dir.mkdirs()
            tools.ffmpeg.writeBytes(ByteArray(1_200_000))
            var callbackValue: Boolean? = null
            val returned = tools.ensureFfmpeg { callbackValue = it }
            assertTrue("a present ffmpeg was not detected", returned && tools.ffmpegReady)
            assertTrue("the callback should still be told", callbackValue == true)
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    /**
     * The status line is what the user sees, so it has to explain the situation
     * rather than imply something is broken.
     *
     * ffmpeg ships in the package now, so the honest reading of "yt-dlp present, ffmpeg
     * absent" is that the unpack has not finished, not that a quality is unavailable. The
     * old wording - "high-quality formats download ffmpeg on demand" - described the
     * shipping arrangement as a permanent limitation, which stopped being true when the
     * executable started arriving inside the jar.
     */
    @Test
    fun theStatusExplainsThePendingFetchRatherThanClaimingAnError() {
        val dir = tempDir()
        try {
            val tools = YtDlpTools(dir)
            dir.mkdirs()
            tools.ytDlp.writeBytes(ByteArray(2_000_000))
            val pending = tools.statusText()
            assertTrue(
                "the pending fetch should read as something in progress: $pending",
                pending.contains("fetching") && !pending.contains("on demand")
            )
            assertFalse(
                "a first-run unpack must not be reported as an error: $pending",
                pending.contains("could not") || pending.contains("error")
            )
            tools.ffmpeg.writeBytes(ByteArray(2_000_000))
            assertTrue(
                "a complete install should say so: ${tools.statusText()}",
                tools.statusText().contains("ffmpeg ready")
            )
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }
}
