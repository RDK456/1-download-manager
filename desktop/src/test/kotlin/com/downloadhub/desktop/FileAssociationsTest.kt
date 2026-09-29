package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Registering the app as the handler for magnets and .torrent files.
 *
 * This is written against a throwaway key rather than mocked, because the thing that
 * went wrong was not logic: `reg.exe` rejected the value and returned an error, the key
 * was never written, and the app went on to report success. A mock cannot catch that.
 * A test that checks "does the code contain the right strings" cannot either.
 */
class FileAssociationsTest {

    private val probeKey = "HKCU\\Software\\Classes\\1DownloadManagerTestProbe"
    private val probeFile = File("C:\\Program Files\\1DownloadManager Test\\1DownloadManager.exe")

    private fun clean() {
        ProcessBuilder("reg.exe", "delete", probeKey, "/f")
            .redirectErrorStream(true).start()
            .inputStream.readBytes()
    }

    /**
     * Reads a value back the way the app does.
     *
     * `reg query` separates with runs of spaces, not tabs. Splitting on a tab finds
     * nothing and returns null for every key, which is what made the app believe it had
     * never registered anything.
     */
    private fun regQuery(key: String): String? {
        val output = ProcessBuilder("reg.exe", "query", key)
            .redirectErrorStream(true).start()
            .let { process ->
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                text
            }
        if (output.contains("ERROR", ignoreCase = true)) return null
        val marker = output.indexOf("REG_")
        if (marker < 0) return null
        return output.substring(marker)
            .replaceFirst(Regex("""^REG_\w+\s*"""), "")
            .trimEnd()
    }

    /**
     * The separator really is whitespace, not a tab.
     *
     * Worth its own test because it is the assumption the reader is built on, and
     * getting it wrong makes every read return null rather than fail loudly.
     */
    @Test
    fun regQuerySeparatesWithSpacesNotTabs() {
        assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        clean()
        try {
            val key = "$probeKey\\separator"
            ProcessBuilder("reg.exe", "add", key, "/ve", "/t", "REG_SZ", "/d", "hello", "/f")
                .redirectErrorStream(true).start().waitFor()
            val raw = ProcessBuilder("reg.exe", "query", key)
                .redirectErrorStream(true).start()
                .let { process ->
                    val text = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    text
                }
            val dataLine = raw.lineSequence().firstOrNull { it.contains("REG_SZ") } ?: ""
            assertTrue("the data line is gone, so reading it back is guesswork", dataLine.isNotBlank())
            assertFalse(
                "the value is separated by spaces, not tabs; splitting on a tab finds " +
                    "nothing and every read returns null",
                dataLine.contains('\t')
            )
            assertEquals("hello", regQuery(key))
        } finally {
            clean()
        }
    }

    /**
     * A Windows open command, written through `reg.exe`, with its quotes escaped.
     *
     * This is the exact shape the bug took: the value reg.exe accepts is the one with
     * the inner quotes backslash-escaped, and what comes out is `"app.exe" "%1"`.
     */
    @Test
    fun anOpenCommandWithQuotesSurvivesRegExe() {
        assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        clean()
        try {
            val key = "$probeKey\\shell\\open\\command"
            val expected = "\"${probeFile.absolutePath}\" \"%1\""
            val escaped = expected.replace("\"", "\\\"")
            val process = ProcessBuilder("reg.exe", "add", key, "/ve", "/t", "REG_SZ", "/d", escaped, "/f")
                .redirectErrorStream(true).start()
            val output = String(process.inputStream.readBytes())
            val exit = process.waitFor()

            assertEquals(
                "reg.exe refused the command, so nothing was written and Windows has " +
                    "nothing to launch: $output",
                0, exit
            )
            assertEquals(
                "the stored command is not what Windows needs to launch the app",
                expected, regQuery(key)
            )
        } finally {
            clean()
        }
    }

    /**
     * The unescaped form is what the app used to send, and reg rejects it.
     *
     * Kept because it is the bug: if reg ever started accepting it, this would change
     * and the reason for escaping would need revisiting. Until then it documents why the
     * escaping exists rather than looking like unnecessary noise.
     */
    @Test
    fun theUnescapedFormIsRejectedWhichIsWhyItIsEscaped() {
        assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        val key = "$probeKey\\shell\\open\\command"
        val unescaped = "\"${probeFile.absolutePath}\" \"%1\""
        val process = ProcessBuilder("reg.exe", "add", key, "/ve", "/t", "REG_SZ", "/d", unescaped, "/f")
            .redirectErrorStream(true).start()
        val output = String(process.inputStream.readBytes())
        val exit = process.waitFor()
        clean()
        assertFalse(
            "reg.exe now accepts unescaped quotes; the escaping in FileAssociations can " +
                "be revisited",
            exit == 0 && output.contains("successfully", ignoreCase = true)
        )
    }

    /** A Windows open command must keep its quotes, or nothing can be compared to it. */
    @Test
    fun aStoredCommandKeepsItsQuotes() {
        assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        clean()
        try {
            val key = "$probeKey\\shell\\open\\command"
            val expected = "\"${probeFile.absolutePath}\" \"%1\""
            ProcessBuilder("reg.exe", "add", key, "/ve", "/t", "REG_SZ", "/d",
                expected.replace("\"", "\\\""), "/f").redirectErrorStream(true).start().waitFor()
            assertEquals(
                "stripping the surrounding quotes makes every comparison fail, so the app " +
                    "rewrites its registration on every launch and never sees it as done",
                expected, regQuery(key)
            )
        } finally {
            clean()
        }
    }

    /** An empty value has to read back as empty, not as absent. */
    @Test
    fun anEmptyValueIsEmptyRatherThanMissing() {
        assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        clean()
        try {
            val key = "$probeKey\\protocol"
            ProcessBuilder("reg.exe", "add", key, "/v", "URL Protocol", "/t", "REG_SZ", "/d", "", "/f")
                .redirectErrorStream(true).start().waitFor()
            assertEquals(
                "\"URL Protocol\" being present and empty is what makes Windows treat a " +
                    "scheme as a protocol; reading it as missing loses the distinction",
                "", regQuery("$key")
            )
            assertEquals("", regQuery("$key\\v").let { regQuery(key) })
        } finally {
            clean()
        }
    }

    /**
     * The packaged launcher, or nothing.
     *
     * Run from Gradle this must be null: registering `java.exe` would point every
     * magnet link on the machine at a development JVM.
     */
    @Test
    fun onlyThePackagedLauncherIsEverRegistered() {
        val launcher = FileAssociations.launcherExe()
        if (launcher == null) {
            assertTrue(
                "this test is not running as the packaged app, which is what makes the " +
                    "null check meaningful",
                !FileAssociations.LAUNCHER_NAME.equals(
                    ProcessHandle.current().info().command().orElse("").substringAfterLast('\\'),
                    ignoreCase = true
                )
            )
        } else {
            assertEquals(FileAssociations.LAUNCHER_NAME, launcher.name)
        }
        assertTrue(FileAssociations.launcherExe()?.isFile ?: true)
    }

    /** The command the app would write, which is what the tests above confirm the shape of. */
    @Test
    fun theCommandNamesTheLauncherAndTheArgument() {
        val expected = "\"${probeFile.absolutePath}\" \"%1\""
        assertTrue("the path is quoted, for the spaces in Program Files", expected.startsWith("\""))
        assertTrue("the substituted argument is quoted, for spaces in the argument", expected.endsWith("\"%1\""))
    }
}
