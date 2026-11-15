package com.downloadhub.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDownloadTest {
    private fun zipOf(dir: File, vararg entries: Pair<String, String>): File {
        val file = File(dir, "pack.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun zipIsExtractedBesideItself() {
        val dir = createTempDir()
        val result = PostDownload.extractZip(zipOf(dir, "a.txt" to "hi", "sub/b.txt" to "yo"))
        assertEquals("hi", File(result!!, "a.txt").readText())
        assertEquals("yo", File(result, "sub/b.txt").readText())
    }

    @Test
    fun entriesEscapingTheFolderAreSkipped() {
        val dir = createTempDir()
        PostDownload.extractZip(zipOf(dir, "../evil.txt" to "x", "ok.txt" to "y"))
        assertFalse(File(dir, "evil.txt").exists())
        assertTrue(File(dir, "pack/ok.txt").exists())
    }

    @Test
    fun aNonZipIsLeftAlone() {
        val file = File(createTempDir(), "notes.txt").apply { writeText("x") }
        assertNull(PostDownload.extractZip(file))
    }
}
