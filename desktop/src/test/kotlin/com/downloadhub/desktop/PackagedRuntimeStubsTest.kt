package com.downloadhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the packaged runtime against the api-ms-win-*.dll stubs coming back.
 *
 * These files are the reason "Failed to launch JVM" was so hard to shake, and it is
 * the kind of regression that never shows up in a test: the app is built, it is
 * installed, and on an ordinary machine it starts perfectly. It only fails on a
 * machine whose security product quarantines or blocks one of them - and then all the
 * user gets is a dialog with no explanation.
 */
class PackagedRuntimeStubsTest {

    private fun runtimeBin(): File? =
        File("build/compose/binaries/main/app/1DownloadManager/runtime/bin")
            .takeIf { it.isDirectory }

    /**
     * The build that runs these is the one that strips the stubs, so they are already
     * gone by the time the tests execute. That is exactly the point being asserted.
     */
    @Test
    fun theApiSetStubsAreNotShipped() {
        val bin = runtimeBin() ?: return // nothing packaged yet; nothing to assert
        val stubs = bin.listFiles { f: File -> f.isFile && f.name.startsWith("api-ms-") }
        assertTrue(
            "The packaged runtime still contains ${stubs?.size} api-ms-*.dll forwarder " +
                "stubs. They are what security software blocks, and blocking any one of " +
                "them stops the JVM starting with no other symptom. See the " +
                "prepareDistributable task.",
            stubs.isNullOrEmpty()
        )
    }

    /**
     * Stripping the stubs must not take the JVM with them. This is the failure a
     * careless glob would cause, and it would be catastrophic and immediate.
     */
    @Test
    fun theJvmAndItsCascadeAreStillThere() {
        val bin = runtimeBin() ?: return
        listOf("server/jvm.dll", "java.dll", "ucrtbase.dll", "vcruntime140.dll", "zip.dll")
            .forEach { name ->
                val f = File(bin, name)
                assertTrue("$name is missing from the packaged runtime", f.isFile)
                assertTrue("$name is empty", f.length() > 0L)
            }
        // jvm.dll is 12 MB. Anything much smaller is a truncated write, which fails
        // the same way as a missing file but is far harder to spot.
        val jvm = File(bin, "server/jvm.dll")
        assertTrue(
            "server/jvm.dll is only ${jvm.length()} bytes; it should be around 12 MB",
            jvm.length() > 8L * 1024 * 1024
        )
    }

    /**
     * jlink strips the launchers, so the packaged app used to have no `java.exe` and
     * the jpackage stub was the only route into the JVM - which is why a failure could
     * only ever be reported as "Failed to launch JVM". The build now adds java.exe back
     * (49 KB) so the package has a second way in that prints the actual error.
     */
    @Test
    fun thereIsAJavaExecutableSoRealErrorsCanBeSeen() {
        val bin = runtimeBin() ?: return
        val java = File(bin, "java.exe")
        assertTrue(
            "runtime/bin/java.exe is missing, so the package can only report " +
                "'Failed to launch JVM' and never the cause",
            java.isFile
        )
        assertTrue("java.exe is empty", java.length() > 0L)
        // It must be the real launcher, not a renamed stub.
        assertTrue(
            "java.exe should be tens of kilobytes, not a stub",
            java.length() in 10_000L..500_000L
        )
    }

    /**
     * The build must refuse to strip anything that is not plausibly a forwarder stub.
     * A future JDK could ship a real component with a name starting "api-ms-", and
     * deleting it would ship a JVM that cannot start.
     */
    @Test
    fun theBuildGuardsAgainstOverStripping() {
        val source = File("build.gradle.kts").readText()
        val task = source.substringAfter("val prepareDistributable")
            .substringBefore("val packageZip")
        assertTrue(
            "the size guard is gone; the build would strip real runtime components",
            task.contains("512L * 1024L")
        )
        assertTrue(
            "the build no longer checks the JVM survived the strip",
            task.contains("server/jvm.dll")
        )
    }

    /**
     * "Failed to launch JVM" says nothing about the cause, and the failure happens
     * before any app code runs, so the app cannot report it. The check has to travel
     * inside the package.
     */
    @Test
    fun theStartupCheckShipsBesideTheLauncher() {
        val app = File("build/compose/binaries/main/app/1DownloadManager")
        if (!app.isDirectory) return
        listOf("Troubleshoot.bat", "Troubleshoot.ps1", "Start 1DownloadManager.bat")
            .forEach { name ->
                val f = File(app, name)
                assertTrue("$name is not shipped next to the launcher", f.isFile)
                assertTrue("$name is empty", f.length() > 0L)
            }
        val source = File("dist-tools/Troubleshoot.ps1").readText()
        listOf(
            "runtime\\release",
            "server\\jvm.dll",
            "api-ms-",
            "extract fully",
            "security software"
        ).forEach { needle ->
            assertTrue(
                "the startup check no longer looks for '$needle', which is one of the " +
                    "things that actually causes this error",
                source.contains(needle)
            )
        }
    }

    /** It must not touch anything, or people will not run it. */
    @Test
    fun theStartupCheckOnlyReads() {
        val source = File("dist-tools/Troubleshoot.ps1").readText()
        listOf("Remove-Item", "del /", "rmdir", "Move-Item", "Set-Content", "New-Item")
            .forEach { forbidden ->
                assertFalse(
                    "the startup check contains '$forbidden'; it is supposed to only " +
                        "read files, so that people are willing to run it",
                    source.contains(forbidden)
                )
            }
    }
}
