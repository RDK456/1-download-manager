package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the dialog scoping in Main.kt.
 *
 * Every dialog used to be composed as a sibling of the `Window`, at the
 * `application` level. Compose Desktop's `Dialog` reads `LocalComposeScene`, which
 * only a `Window` provides, so the moment one of those flags flipped it threw
 *
 *     IllegalStateException: CompositionLocal LocalComposeScene not provided
 *
 * during *recomposition*, taking the whole JVM down. The packaged launcher then
 * reported that with the only message it has - "Failed to launch JVM" - so the
 * reported symptom pointed at the runtime and the actual fault, in application code,
 * never appeared.
 *
 * It is worth being explicit about how this was missed: every end-to-end check passed
 * because none of them opened a dialog. Downloads, the capture endpoint and the tray
 * were all verified, so "verified end to end" was true and still missed a crash on
 * every button.
 */
class DialogScopingTest {

    private val source = File("src/main/kotlin/com/downloadhub/desktop/Main.kt")

    private val dialogs = listOf(
        "AddDownloadDialog", "SettingsDialog", "UpdateDialog", "CloseDialog"
    )

    /**
     * Tracks brace depth to decide whether each dialog is lexically inside the Window
     * block. Comparing the positions of "Window(" and the dialog names is not enough:
     * both orderings pass that check, which is precisely how this got through once.
     */
    private fun depthOfDialogs(): List<Pair<String, Int>> {
        var depth = 0
        var windowDepth = -1
        val found = mutableListOf<Pair<String, Int>>()
        source.readLines().forEach { line ->
            val code = line.substringBefore("//")
            if (windowDepth < 0 && code.trimStart().startsWith("Window(")) windowDepth = depth + 1
            dialogs.forEach { name ->
                if (code.contains("$name(")) found += name to depth
            }
            depth += Regex("\\{").findAll(line).count() - Regex("\\}").findAll(line).count()
        }
        return found
    }

    @Test
    fun theWindowIsFoundSoTheOtherChecksMeanSomething() {
        val text = source.readText()
        assertTrue("no Window in Main.kt; this test is checking nothing", text.contains("Window("))
    }

    @Test
    fun everyDialogIsComposedInsideTheWindow() {
        val found = depthOfDialogs()
        assertEquals(
            "not every dialog was found in Main.kt, so this test is only partly " +
                "checking. Found: ${found.map { it.first }}",
            dialogs.size,
            found.size
        )

        // The Window lambda opens at the depth where "Window(" appears; a dialog must
        // be at least that deep to be inside it.
        var depth = 0
        var windowDepth = -1
        source.readLines().forEach { line ->
            val code = line.substringBefore("//")
            if (windowDepth < 0 && code.trimStart().startsWith("Window(")) windowDepth = depth + 1
            depth += Regex("\\{").findAll(line).count() - Regex("\\}").findAll(line).count()
        }
        assertTrue("could not locate the Window block", windowDepth > 0)

        val outside = found.filter { it.second < windowDepth }
        assertTrue(
            "these dialogs are composed outside the Window and will throw " +
                "\"CompositionLocal LocalComposeScene not provided\": " +
                outside.joinToString { "${it.first} at depth ${it.second}" } +
                " (the Window body starts at depth $windowDepth)",
            outside.isEmpty()
        )
    }

    /**
     * The dialogs are nested in the Window, which is itself inside `if (visible)`.
     * So hiding to the tray disposes them along with the window - which is the
     * behaviour the comment in Main.kt claims, and which is worth having: a dialog
     * cannot be used while its window is off screen, and leaving it composed would
     * mean it reappeared with stale contents when the window came back.
     */
    @Test
    fun theWindowItselfIsInsideTheVisibleCheck() {
        var depth = 0
        var visibleDepth = -1
        var windowDepth = -1
        source.readLines().forEach { line ->
            val code = line.substringBefore("//")
            if (visibleDepth < 0 && code.trimStart().startsWith("if (visible)")) visibleDepth = depth + 1
            if (windowDepth < 0 && code.trimStart().startsWith("Window(")) windowDepth = depth + 1
            depth += Regex("\\{").findAll(line).count() - Regex("\\}").findAll(line).count()
        }
        assertTrue("could not find if (visible)", visibleDepth > 0)
        assertTrue("could not find the Window", windowDepth > 0)
        assertTrue(
            "the Window is not inside if (visible), so the dialogs would survive being " +
                "hidden to the tray",
            windowDepth > visibleDepth
        )
    }

    /**
     * The dialogs render on Material's light surface unless the app declares a dark
     * scheme, which put a white box - and near-white body text on white - in the
     * middle of a dark window.
     */
    @Test
    fun theAppDeclaresADarkSchemeSoDialogsAreNotWhite() {
        val text = source.readText()
        assertTrue(
            "no MaterialTheme in Main.kt, so every dialog falls back to Material's " +
                "light surface",
            text.contains("MaterialTheme(")
        )
        val theme = File("src/main/kotlin/com/downloadhub/desktop/AppTheme.kt")
        assertTrue("AppTheme.kt is missing", theme.isFile)
        assertTrue(
            "the scheme must be dark, not the default light one",
            theme.readText().contains("darkColorScheme(")
        )
    }
}
