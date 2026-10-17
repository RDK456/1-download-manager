package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.WidthClass
import com.downloadhub.app.ui.expressive.model.widthClassFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowClassTest {
    @Test fun boundariesFollowTheMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, widthClassFor(320))
        assertEquals(WidthClass.COMPACT, widthClassFor(599))
        assertEquals(WidthClass.MEDIUM, widthClassFor(600))
        assertEquals(WidthClass.MEDIUM, widthClassFor(839))
        assertEquals(WidthClass.EXPANDED, widthClassFor(840))
        assertEquals(WidthClass.EXPANDED, widthClassFor(1280))
    }

    @Test fun onlyCompactKeepsTheBottomBar() {
        assertFalse(WidthClass.COMPACT.usesRail)
        assertTrue(WidthClass.MEDIUM.usesRail)
        assertTrue(WidthClass.EXPANDED.usesRail)
    }

    @Test fun onlyExpandedShowsAPane() {
        assertFalse(WidthClass.COMPACT.showsDetailPane)
        assertFalse(WidthClass.MEDIUM.showsDetailPane)
        assertTrue(WidthClass.EXPANDED.showsDetailPane)
    }
}
