package com.downloadhub.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A string literal once shipped 8,592 characters of mojibake: the text "checking
 * for a newer release" followed by garbage that rendered as an unreadable wall on
 * the screen. It came from a script that rewrote a file in the wrong encoding.
 *
 * These tests read the real source tree and fail if that ever happens again, which
 * no compile-time check can catch because mojibake is still valid Kotlin.
 */
class SourceEncodingTest {

    private val sourceRoot: File = locateSources()

    private fun kotlinFiles(): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun locateSources(): File {
        // Gradle runs tests with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/java from ${File(".").absolutePath}")
    }

    @Test
    fun noSourceFileIsMissing() {
        assertTrue("no Kotlin sources found under ${sourceRoot.absolutePath}", kotlinFiles().isNotEmpty())
    }

    @Test
    fun noStringLiteralContainsMojibake() {
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            file.readLines().forEachIndexed { index, line ->
                // U+FFFD and the classic "A-circumflex-tilde" lead bytes of
                // double-encoded UTF-8 both show up as these ranges.
                if (line.contains('\u00C3') || line.contains('\u00C2') || line.contains('\uFFFD')) {
                    offenders += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(
            "mojibake in string literals at ${offenders.joinToString()}. " +
                "Rewrite the file as UTF-8 rather than through a re-encoding script.",
            offenders.isEmpty()
        )
    }

    @Test
    fun noSourceLineIsAbsurdlyLong() {
        // The corrupt literal was 8,675 characters on one line. A runaway blob like
        // that is always an encoding accident, never real source.
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (line.length > 400) offenders += "${file.name}:${index + 1} (${line.length} chars)"
            }
        }
        assertTrue("suspiciously long source lines: ${offenders.joinToString()}", offenders.isEmpty())
    }

    @Test
    fun noSourceFileContainsNullBytes() {
        val offenders = mutableListOf<String>()
        for (file in kotlinFiles()) {
            if (file.readBytes().any { it == 0.toByte() }) offenders += file.name
        }
        assertTrue("null bytes in ${offenders.joinToString()}", offenders.isEmpty())
    }
}
