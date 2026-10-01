package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section lists whatever a link holds - a playlist, an album, a channel, or
 * one video - so the parsing has to treat them all as the same thing, and has
 * to survive the shapes flat listings actually arrive in: explicit nulls for
 * unavailable videos, dupes across overlapping playlists, and rows with no id.
 */
class YouTubeSectionTest {

    private fun flatPlaylist() = """
        {
          "_type": "playlist",
          "title": "Synthwave Mix",
          "entries": [
            {"_type": "url", "id": "KPh2Efv66Aw", "title": "Mary Had a Little Lamb", "duration": 125, "uploader": "Cocomelon"},
            null,
            {"_type": "url", "id": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "duration": 212.0},
            {"_type": "url", "id": "KPh2Efv66Aw", "title": "Mary Had a Little Lamb", "duration": 125},
            {"_type": "url", "title": "no id here", "duration": 10},
            {"_type": "url", "id": "PLabcdefghij0123456789ABCDEFGH12", "title": "a playlist id, not a video"}
          ]
        }
    """.trimIndent()

    @Test
    fun flatPlaylistKeepsOneRowPerVideo() {
        val listing = parseYouTubeListing(flatPlaylist())
        assertEquals("Synthwave Mix", listing?.title)
        assertEquals(listOf("KPh2Efv66Aw", "dQw4w9WgXcQ"), listing?.entries?.map { it.id })
        assertEquals(false, listing?.single)
    }

    @Test
    fun entryFieldsSurvive() {
        val first = parseYouTubeListing(flatPlaylist())?.entries?.first()
        assertEquals("Mary Had a Little Lamb", first?.title)
        assertEquals(125L, first?.durationSeconds)
        assertEquals("Cocomelon", first?.uploader)
        assertEquals("https://www.youtube.com/watch?v=KPh2Efv66Aw", first?.url)
    }

    @Test
    fun singleVideoIsOneEntryMarkedSingle() {
        val json = """
            {
              "_type": "video",
              "id": "KPh2Efv66Aw",
              "title": "Mary Had a Little Lamb",
              "duration": 125,
              "uploader": "Cocomelon"
            }
        """.trimIndent()
        val listing = parseYouTubeListing(json)
        assertEquals(true, listing?.single)
        assertEquals(1, listing?.entries?.size)
        assertEquals("Mary Had a Little Lamb", listing?.title)
    }

    @Test
    fun nothingUsableIsNothing() {
        assertNull(parseYouTubeListing(""))
        assertNull(parseYouTubeListing("not json"))
        assertNull(parseYouTubeListing("""{"_type": "playlist", "entries": [null, null]}"""))
        assertNull(parseYouTubeListing("""{"_type": "unknown_thing"}"""))
    }

    @Test
    fun knownIdsAreDroppedByIdNotTitle() {
        val entries = listOf(
            YouTubeEntry("KPh2Efv66Aw", "Same Title", 1),
            YouTubeEntry("dQw4w9WgXcQ", "Same Title", 2)
        )
        val remaining = filterKnownEntries(entries) { it == "KPh2Efv66Aw" }
        assertEquals(listOf("dQw4w9WgXcQ"), remaining.map { it.id })
    }

    @Test
    fun idsAreFoundWhateverShapeTheLinkArrivedIn() {
        val id = "KPh2Efv66Aw"
        mapOf(
            "https://www.youtube.com/watch?v=$id" to id,
            "https://www.youtube.com/watch?v=$id&t=42s" to id,
            "https://youtu.be/$id" to id,
            "https://www.youtube.com/shorts/$id" to id,
            "https://www.youtube.com/embed/$id" to id,
            "https://www.youtube.com/live/$id" to id,
            "https://music.youtube.com/watch?v=$id" to id
        ).forEach { (url, expected) ->
            assertEquals("no id found in $url", expected, youTubeIdFromUrl(url))
        }
        assertNull(youTubeIdFromUrl("https://example.com/watch?v=$id"))
        assertNull(youTubeIdFromUrl("not a link"))
    }
}
