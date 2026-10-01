package com.downloadhub.app.ui

import com.downloadhub.app.ui.AppDestination.ABOUT
import com.downloadhub.app.ui.AppDestination.DOWNLOADS
import com.downloadhub.app.ui.AppDestination.DOWNLOAD_SETTINGS
import com.downloadhub.app.ui.AppDestination.SEARCH
import com.downloadhub.app.ui.AppDestination.SETTINGS
import com.downloadhub.app.ui.AppDestination.THEMES
import com.downloadhub.app.ui.AppDestination.TORRENTS
import com.downloadhub.app.ui.AppDestination.YOUTUBE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back gesture used to close the app from Download settings and Themes,
 * because the handler was only enabled for Settings and About. These tests pin
 * the three levels the gesture has to walk: sub-page -> parent -> home, then a
 * confirmation instead of an immediate exit.
 */
class NavStackTest {

    @Test
    fun startsOnTheDownloadsTab() {
        val nav = NavStack()
        assertEquals(DOWNLOADS, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun backFromDownloadSettingsReturnsToSettingsNotOutOfTheApp() {
        val nav = NavStack()
            .navigate(SETTINGS)
            .navigate(DOWNLOAD_SETTINGS)

        assertEquals(DOWNLOAD_SETTINGS, nav.current)
        val parent = nav.back()
        assertEquals("back must land on the parent page", SETTINGS, parent?.current)
        assertTrue("Settings must still offer back", parent?.canGoBack == true)
    }

    @Test
    fun backFromThemesReturnsToSettings() {
        val parent = NavStack().navigate(SETTINGS).navigate(THEMES).back()
        assertEquals(SETTINGS, parent?.current)
    }

    @Test
    fun backFromAboutReturnsToSettings() {
        val parent = NavStack().navigate(SETTINGS).navigate(ABOUT).back()
        assertEquals(SETTINGS, parent?.current)
    }

    @Test
    fun backFromSettingsReturnsToTheListTab() {
        val home = NavStack().navigate(SETTINGS).back()
        assertEquals(DOWNLOADS, home?.current)
        assertFalse("the list tab is where back asks to exit", home?.canGoBack == true)
    }

    @Test
    fun backOnARootTabAsksInsteadOfExiting() {
        // null means "no parent left", which is what the exit confirmation keys off.
        assertNull(NavStack().back())
        assertNull(NavStack().navigate(SETTINGS).back()?.back())
    }

    @Test
    fun theFullChainNeedsExactlyTwoBacksToReachHome() {
        var nav = NavStack().navigate(SETTINGS).navigate(DOWNLOAD_SETTINGS)
        var steps = 0
        while (nav.back() != null) {
            nav = nav.back()!!
            steps++
        }
        assertEquals(2, steps)
    }

    @Test
    fun switchingTabsClearsTheOtherTabsHistory() {
        val nav = NavStack()
            .navigate(SETTINGS)
            .navigate(THEMES)
            .navigate(TORRENTS)

        assertEquals(TORRENTS, nav.current)
        assertFalse("back from a tab must not enter the old tab's sub-pages", nav.canGoBack)
    }

    @Test
    fun backFromASubPageOpenedFromTorrentsReturnsToTorrents() {
        val nav = NavStack().navigate(TORRENTS).navigate(SETTINGS)
        assertEquals(SETTINGS, nav.current)
        assertEquals(TORRENTS, nav.root)
        assertEquals("back must return to the tab it was opened from", TORRENTS, nav.back()?.current)
    }

    @Test
    fun onlyTheListTabsAreRoots() {
        assertTrue(DOWNLOADS.isRoot)
        assertTrue(TORRENTS.isRoot)
        assertTrue(SEARCH.isRoot)
        assertTrue(YOUTUBE.isRoot)
        assertFalse(SETTINGS.isRoot)
        assertFalse(DOWNLOAD_SETTINGS.isRoot)
        assertFalse(THEMES.isRoot)
        assertFalse(ABOUT.isRoot)
    }

    @Test
    fun navigatingToTheCurrentPageDoesNotStackItTwice() {
        val nav = NavStack().navigate(THEMES).navigate(THEMES)
        assertEquals(1, nav.entries.size)
        assertEquals(DOWNLOADS, nav.back()?.current)
    }

    @Test
    fun theStackCannotGrowWithoutBound() {
        var nav = NavStack()
        repeat(50) { nav = nav.navigate(SETTINGS) }
        assertTrue("stack should be capped", nav.entries.size <= 16)
    }
}
