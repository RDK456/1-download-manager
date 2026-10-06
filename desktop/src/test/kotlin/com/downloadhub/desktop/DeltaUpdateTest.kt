package com.downloadhub.desktop

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaUpdateTest {
    private fun temp(): File = createTempDir("delta").apply { deleteOnExit() }

    private fun write(root: File, path: String, text: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun entry(root: File, path: String) = File(root, path).let { UpdateFile(path, DeltaUpdate.sha256(it), it.length()) }

    /** The new release, built in [release]: what its file list would say. */
    private fun manifest(release: File, deltaPaths: List<String>?, vararg paths: String) =
        UpdateManifest("2.0.0", paths.map { entry(release, it) }, deltaPaths?.let { UpdateDelta("x-delta.zip", "1.0.0", it) })

    @Test
    fun `only files that differ are needed, and the delta must hold all of them`() {
        val installed = temp()
        val release = temp()
        write(installed, "app/a.jar", "old")
        write(installed, "app/b.jar", "same")
        write(release, "app/a.jar", "new")
        write(release, "app/b.jar", "same")
        write(release, "app/c.jar", "added")
        val full = manifest(release, listOf("app/a.jar", "app/c.jar"), "app/a.jar", "app/b.jar", "app/c.jar")
        val changed = DeltaUpdate.changedFiles(full, installed)
        assertEquals(listOf("app/a.jar", "app/c.jar"), changed.map { it.path })
        assertTrue(DeltaUpdate.covers(full, changed))
        val partial = manifest(release, listOf("app/a.jar"), "app/a.jar", "app/b.jar", "app/c.jar")
        assertFalse("a delta missing a changed file must fall back to the installer", DeltaUpdate.covers(partial, changed))
        assertFalse("no delta at all must fall back", DeltaUpdate.covers(manifest(release, null, "app/a.jar"), changed))
    }

    /** The exact shape packageUpdateDelta writes, so the release and the app agree. */
    @Test
    fun `the file list the release publishes is read`() {
        val json = """
            {
              "version": "1.5.6",
              "files": [
                {"path": "app/desktop-1.jar", "sha256": "ab", "size": 3},
                {"path": "runtime/lib/modules", "sha256": "cd", "size": 4}
              ],
              "delta": {"asset": "1-download-manager-1.5.6-delta.zip", "from": "1.5.5", "paths": ["app/desktop-1.jar"]}
            }
        """.trimIndent()
        val manifest = DeltaUpdate.parse(json)
        assertEquals("1.5.6", manifest.version)
        assertEquals(listOf("app/desktop-1.jar", "runtime/lib/modules"), manifest.files.map { it.path })
        assertEquals(listOf("app/desktop-1.jar"), manifest.delta?.paths)
        assertEquals(null, DeltaUpdate.parse("""{"version": "1.5.6", "files": []}""").delta)
    }

    @Test
    fun `only jars in app that the new release lacks are stale`() {
        val installed = temp()
        val release = temp()
        write(installed, "app/desktop-old.jar", "x")
        write(installed, "app/desktop-new.jar", "x")
        write(installed, "app/notes.txt", "mine")
        write(release, "app/desktop-new.jar", "x")
        val stale = DeltaUpdate.staleJars(manifest(release, null, "app/desktop-new.jar"), installed)
        assertEquals(listOf("desktop-old.jar"), stale.map { it.name })
    }

    @Test
    fun `staging refuses a file that does not match its hash`() {
        val release = temp()
        write(release, "app/a.jar", "new")
        val wanted = listOf(entry(release, "app/a.jar"))
        val zip = File(temp(), "d.zip")
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("app/a.jar")); it.write("tampered".toByteArray()); it.closeEntry() }
        val failed = runCatching { DeltaUpdate.stage(zip, wanted, File(temp(), "staging")) }
        assertTrue(failed.isFailure)
    }

    private fun runApply(root: File, staging: File, files: List<String>, stale: List<File>): String {
        val result = File(staging.parentFile, "result.txt")
        DeltaUpdate.launchApply(staging, files, stale, root, null, result, File(staging.parentFile, "delta.log"), waitPid = 999_999).getOrThrow()
        val deadline = System.currentTimeMillis() + 60_000
        while (!result.isFile && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return result.readText().trim()
    }

    @Test
    fun `the apply step swaps the files in and removes stale jars`() {
        val root = temp()
        write(root, "app/a.jar", "old")
        val stale = write(root, "app/desktop-old.jar", "x")
        val work = temp()
        val staging = File(work, "delta-2.0.0")
        write(staging, "app/a.jar", "new")
        write(staging, "app/c.jar", "added")
        val result = runApply(root, staging, listOf("app/a.jar", "app/c.jar"), listOf(stale))
        assertTrue("the apply step reported: $result", result.startsWith("0|delta|"))
        assertEquals("new", File(root, "app/a.jar").readText())
        assertEquals("added", File(root, "app/c.jar").readText())
        assertFalse(stale.exists())
    }

    @Test
    fun `a file that cannot be replaced puts every replaced file back`() {
        val root = temp()
        write(root, "app/a.jar", "old-a")
        val locked = write(root, "app/b.jar", "old-b")
        val work = temp()
        val staging = File(work, "delta-2.0.0")
        write(staging, "app/a.jar", "new-a")
        write(staging, "app/b.jar", "new-b")
        RandomAccessFile(locked, "rw").use { file ->
            file.channel.lock().use {
                val result = runApply(root, staging, listOf("app/a.jar", "app/b.jar"), emptyList())
                assertTrue("the apply step reported: $result", result.startsWith("1|delta|"))
            }
        }
        assertEquals("a.jar must be restored when b.jar could not be replaced", "old-a", File(root, "app/a.jar").readText())
        assertEquals("old-b", File(root, "app/b.jar").readText())
    }
}
