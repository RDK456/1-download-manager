package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadGroupsTest {
    private fun item(id: String, group: String, status: DownloadStatus, done: Long) = DownloadItem(
        id = id, url = "u$id", fileName = "Game.part$id.rar", status = status,
        bytesDownloaded = done, totalBytes = 100, groupId = group
    )

    @Test
    fun batchBecomesOneRowAndLoneItemsStay() {
        val items = listOf(
            item("1", "g", DownloadStatus.COMPLETED, 100),
            item("x", "", DownloadStatus.QUEUED, 0),
            item("2", "g", DownloadStatus.RUNNING, 50)
        )
        val rows = DownloadGroups.collapse(items)
        assertEquals(listOf("group:g", "x"), rows.map { it.id })
        assertEquals(150L, rows[0].bytesDownloaded)
        assertEquals(200L, rows[0].totalBytes)
        assertEquals(DownloadStatus.RUNNING, rows[0].status)
        assertEquals(listOf("1", "2"), DownloadGroups.membersOf(items, "group:g").map { it.id })
    }

    @Test
    fun singleMemberGroupIsNotCollapsed() {
        val items = listOf(item("1", "g", DownloadStatus.QUEUED, 0))
        assertEquals(items, DownloadGroups.collapse(items))
    }
}
