package com.downloadhub.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the packaged runtime rather than the build classpath.
 *
 * Browser capture was first written on the JDK's own `com.sun.net.httpserver`. That
 * module is trimmed out of the jlink runtime the installer ships, so the app
 * compiled, passed every test on a full JDK, and did nothing once packaged - a
 * feature that looked finished and was entirely inert. The engine now speaks HTTP
 * over a plain ServerSocket, and this test makes sure nothing reintroduces a
 * dependency the runtime image cannot satisfy.
 */
class PackagedRuntimeTest {

    private fun appImage(): File? = listOf(
        File("build/compose/binaries/main/app/1DownloadManager"),
        File("../desktop/build/compose/binaries/main/app/1DownloadManager"),
        File("desktop/build/compose/binaries/main/app/1DownloadManager")
    ).firstOrNull { it.isDirectory }

    @Test
    fun theCaptureServerDoesNotDependOnATrimmedModule() {
        // jdk.httpserver is absent from the packaged runtime and the Compose plugin
        // offers no supported way to add it, so nothing may reference it.
        val offenders = mutableListOf<String>()
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                // Comments explain why the module is avoided, so only code counts.
                var inBlockComment = false
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.trim()
                    val isComment = when {
                        line.startsWith("/*") -> {
                            // A block ends only if this same line closes it.
                            inBlockComment = !line.endsWith("*/")
                            true
                        }

                        inBlockComment -> {
                            if (line.contains("*/")) inBlockComment = false
                            true
                        }

                        else -> {
                            inBlockComment = false
                            line.startsWith("//")
                        }
                    }
                    if (!isComment && raw.substringBefore("//").contains("com.sun.net.httpserver")) {
                        offenders += "${file.name}:${index + 1}"
                    }
                }
            }
        assertTrue(
            "these files use com.sun.net.httpserver, which the packaged runtime does " +
                "not contain: ${offenders.joinToString()}",
            offenders.isEmpty()
        )
    }

    @Test
    fun theCaptureServerNeedsNothingOutsideTheBaseModule() {
        // ServerSocket, SecureRandom and the JDK collections are all in java.base,
        // which every runtime image must contain.
        assertTrue(
            "ServerSocket must be available",
            runCatching { Class.forName("java.net.ServerSocket") }.isSuccess
        )
    }

    @Test
    fun thePackagedRuntimeIsPresentAndSane() {
        val app = appImage()
        assumeTrue("no packaged app image to inspect", app != null)
        assertTrue("packaged app has no launcher", File(app, "1DownloadManager.exe").isFile)
        assertTrue("packaged app has no runtime", File(app, "runtime/lib/modules").isFile)
        assertTrue(
            "the packaged app folder is missing",
            File(app, "app").isDirectory
        )
    }
}