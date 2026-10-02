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
     * jli.dll is what java.exe loads first, and a bad one kills the diagnostic
     * instantly.
     *
     * The 1.4.30 zip shipped a jli.dll that was 89,720 bytes of x86-64 machine code
     * with no PE header at all - the right length, so the file-count and size checks
     * above all passed and every version of that file was 89,720 bytes, so a size
     * comparison would have passed too. The packaged java.exe died with
     * 0xC000012F, STATUS_INVALID_IMAGE_FORMAT, which is the Windows loader saying
     * this is not a DLL.
     *
     * The app itself was fine - the launcher loads server/jvm.dll and never touches
     * jli.dll - so the only casualty was the tool added to diagnose startup failures.
     * jli.dll is an unsigned launcher DLL and the file security software most often
     * flags; a flagged file can be rewritten in place rather than removed, which is
     * exactly what the bytes looked like.
     */
    @Test
    fun thePackagedJliIsARealDll() {
        val bin = runtimeBin() ?: return
        val jli = File(bin, "jli.dll")
        assertTrue("runtime/bin/jli.dll is missing", jli.isFile)
        assertTrue("jli.dll is empty", jli.length() > 0L)
        assertTrue(
            "jli.dll has no PE header, so it is not a DLL and the Windows loader will " +
                "refuse it with STATUS_INVALID_IMAGE_FORMAT. This is what shipped in " +
                "1.4.30; see the prepareDistributable task, which now rewrites it from " +
                "the build JDK every build.",
            startsWithMz(jli)
        )
    }

    /**
     * java.exe and jli.dll must be the same build of the JDK.
     *
     * They are copied together now, and the reason to say so is that a mismatched pair
     * fails at load time rather than at run time, with nothing in the app's own code to
     * point at - which is the failure mode this whole file exists to catch.
     */
    @Test
    fun theLauncherAndItsJliAreTheSameBuild() {
        val bin = runtimeBin() ?: return
        val java = File(bin, "java.exe")
        val jli = File(bin, "jli.dll")
        if (!java.isFile || !jli.isFile) return
        val jdkBins = listOfNotNull(System.getProperty("jdk.home"), System.getProperty("java.home"))
            .map { File(it, "bin") }
            .filter { it.isDirectory }
        listOf("java.exe", "jli.dll").forEach { name ->
            val packaged = File(bin, name)
            val jdk = jdkBins.map { File(it, name) }.firstOrNull { it.isFile } ?: return@forEach
            assertTrue(
                "$name in the package differs from the build JDK's copy, so the two " +
                    "were not written by the same build",
                packaged.length() == jdk.length() &&
                    packaged.readBytes().contentEquals(jdk.readBytes())
            )
        }
    }

    /**
     * The build must not trust whatever is on disk for these two files.
     *
     * It rewrote them only when they were missing, which is why a file that was present
     * but had been replaced with something the right size went straight out in a release.
     */
    @Test
    fun theBuildVerifiesTheLauncherFilesItShips() {
        val source = File("build.gradle.kts").readText()
        val task = source.substringAfter("val prepareDistributable")
            .substringBefore("val packageZip")
        assertTrue(
            "the build must copy jli.dll as well as java.exe; they are a pair and one " +
                "without the other is a launcher that cannot start",
            task.contains("\"jli.dll\"")
        )
        assertTrue(
            "and it must check for a PE header, or a file of the right size that is not " +
                "a DLL passes every other check",
            task.contains("0x4D.toByte()") && task.contains("0x5A.toByte()")
        )
        assertTrue(
            "and it must compare against the build JDK rather than only testing for " +
                "absence, which is what let the bad file through",
            task.contains("contentEquals")
        )
    }

    /** Whether a file begins with the two bytes every Windows executable starts with. */
    private fun startsWithMz(file: File): Boolean {
        if (!file.isFile || file.length() < 2L) return false
        val head = file.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        return head[0] == 0x4D.toByte() && head[1] == 0x5A.toByte()
    }

    /**
     * The build must actually run the launcher, not just compare files.
     *
     * Everything else in this file compares the package against what we meant to ship.
     * That is a different claim from "the package can start", and 1.4.30 is the proof:
     * its jli.dll was exactly the right length and completely the wrong file, and every
     * comparison the build had was satisfied by it. Running `java.exe -version` is the
     * smallest thing that has to work for the package to be useful, it takes about a
     * second, and it is the only check that cannot be fooled by a file of the right size.
     */
    @Test
    fun theBuildRunsThePackagedLauncherBeforeShippingIt() {
        val source = File("build.gradle.kts").readText()
        val task = source.substringAfter("val prepareDistributable")
            .substringBefore("val packageZip")
        assertTrue(
            "the build must run the packaged java.exe; comparing files cannot tell a " +
                "valid DLL from one of the right size that is not a DLL",
            task.contains("ProcessBuilder") && task.contains("\"-version\"")
        )
        assertTrue(
            "and a non-zero exit must stop the build",
            task.contains("probe.exitValue() != 0")
        )
        assertTrue(
            "with a timeout, so a launcher that hangs is reported as a hang rather " +
                "than stalling the build forever",
            task.contains("waitFor(60L")
        )
        assertTrue(
            "and the output has to go somewhere nobody is reading, or the child blocks " +
                "on a full pipe and the timeout reports the wrong thing",
            task.contains("redirectOutput")
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
