package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonFolderNameTest {
    @Test
    fun repackPartsShareTheirGameName() {
        val names = (1..30).map { "CoD_MW4_-_fitgirl-repacks.site_-_.part%02d.rar".format(it) }
        assertEquals("CoD_MW4", BatchLinks.commonFolderName(names))
    }

    @Test
    fun otherSetsKeepTheirSharedPrefix() {
        assertEquals("holiday", BatchLinks.commonFolderName(listOf("holiday-1.jpg", "holiday-2.jpg")))
    }

    @Test
    fun unrelatedFilesGiveNothing() {
        assertEquals("", BatchLinks.commonFolderName(listOf("a.zip", "b.zip")))
    }
}
