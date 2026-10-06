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
     * The dialogs render on Material's light surface unless the app supplies a scheme of
     * its own, which put a white box - and near-white body text on white - in the middle
     * of a dark window.
     *
     * This used to look for the literal `MaterialTheme(` in Main.kt. The window now goes
     * through `ProvideDesktopTheme`, which builds the scheme and wraps `MaterialTheme`
     * itself, so the spelling changed while the fault it guards against did not. The
     * assertion is on what is provided rather than on how it is spelled - a test that
     * fails when correct code is renamed is a test that gets deleted instead of fixed,
     * and then the fault comes back.
     *
     * Both a dark and a light scheme are required: the app now offers a light theme, and
     * a light theme with a hard-wired dark scheme puts the same white box back.
     */
    @Test
    fun theAppSuppliesAMaterialSchemeSoDialogsAreNotWhite() {
        val text = source.readText()
        assertTrue(
            "no theme provided in Main.kt, so every dialog falls back to Material's " +
                "default light surface",
            text.contains("ProvideDesktopTheme(")
        )
        val theme = File("src/main/kotlin/com/downloadhub/desktop/AppTheme.kt")
        assertTrue("AppTheme.kt is missing", theme.isFile)
        val themeText = theme.readText()
        assertTrue(
            "the dark mode must build a dark scheme, not the default light one",
            themeText.contains("darkColorScheme(")
        )
        assertTrue(
            "the light mode must build a light scheme; with only a dark one, choosing " +
                "Light renders every dialog as Material's default",
            themeText.contains("lightColorScheme(")
        )
    }

    /**
     * The flat-drawn parts of the window have to read the theme too.
     *
     * A scheme reaches Material components and nothing else. Rows, headers, the sidebar
     * and the status strip are flat fills, and they were literals - which is why the
     * desktop app looked identical however the Material theme was set, and why a theme
     * picker would have changed nothing a user could see.
     */
    @Test
    fun theFlatDrawnFillsReadTheThemeRatherThanLiterals() {
        val dir = File("src/main/kotlin/com/downloadhub/desktop")
        val allowed = setOf(
            // The app's own scheme, and the dialog properties beside it.
            "AppTheme.kt",
            // The app icon: brand art, the same indigo in the tray whatever the theme.
            "AppArtwork.kt",
            // Browser brand marks: Firefox is orange on every website and so is here.
            "BrowserIntegrationMenu.kt",
            // A scrim is black at every theme - that is what a scrim is.
            "UpdateDialog.kt",
            "MenuBar.kt"
        )
        val offenders = dir.listFiles { f: java.io.File -> f.extension == "kt" }
            .orEmpty()
            .filter { it.name !in allowed }
            .mapNotNull { file ->
                Regex("Color\\(0x[0-9A-Fa-f]{8}\\)")
                    .find(file.readText())
                    ?.let { "${file.name}: ${it.value}" }
            }
        assertTrue(
            "these files paint with fixed colours, so they ignore the chosen theme:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
