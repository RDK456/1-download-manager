package com.downloadhub.desktop

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserInstallTest {
    private val sample = """
HKEY_LOCAL_MACHINE\SOFTWARE\Clients\StartMenuInternet\Brave
    (Default)    REG_SZ    Brave

HKEY_LOCAL_MACHINE\SOFTWARE\Clients\StartMenuInternet\Brave\shell\open\command
    (Default)    REG_SZ    "C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe"

HKEY_LOCAL_MACHINE\SOFTWARE\Clients\StartMenuInternet\Firefox-B00505CC518AC99B
    (Default)    REG_SZ    Zen Browser

HKEY_LOCAL_MACHINE\SOFTWARE\Clients\StartMenuInternet\Firefox-B00505CC518AC99B\shell\open\command
    (Default)    REG_SZ    "D:\zen\zen.exe"

HKEY_LOCAL_MACHINE\SOFTWARE\Clients\StartMenuInternet\IEXPLORE.EXE\shell\open\command
    (Default)    REG_SZ    C:\Program Files\Internet Explorer\iexplore.exe
""".trimIndent()

    @Test
    fun `registered browsers are read with their names, and ones without a build are left out`() {
        val found = BrowserInstall.parse(sample).sortedBy { it.name }
        assertEquals(listOf("Brave", "Zen Browser"), found.map { it.name })
        assertEquals(BrowserFamily.CHROMIUM, found[0].family)
        assertEquals(File("D:\\zen\\zen.exe"), found[1].exe)
        assertEquals(BrowserFamily.GECKO, found[1].family)
    }

    @Test
    fun `the family comes from the program's name`() {
        assertEquals(BrowserFamily.CHROMIUM, BrowserInstall.familyOf("C:\\x\\msedge.exe"))
        assertEquals(BrowserFamily.GECKO, BrowserInstall.familyOf("C:\\x\\LibreWolf.exe"))
        assertNull(BrowserInstall.familyOf("C:\\x\\iexplore.exe"))
    }

    @Test
    fun `an xpi has the manifest at its root`() {
        val dir = createTempDir("ext")
        try {
            File(dir, "manifest.json").writeText("{}")
            File(dir, "background.js").writeText("")
            val xpi = BrowserInstall.packXpi(dir, File(dir.parentFile, dir.name + ".xpi"))
            ZipFile(xpi).use { zip -> assertEquals(setOf("background.js", "manifest.json"), zip.entries().toList().map { it.name }.toSet()) }
            xpi.delete()
        } finally {
            dir.deleteRecursively()
        }
    }
}
