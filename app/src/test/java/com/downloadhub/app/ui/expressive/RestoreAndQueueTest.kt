package com.downloadhub.app.ui.expressive

import androidx.compose.runtime.saveable.SaverScope
import com.downloadhub.app.ui.SearchScreenState
import com.downloadhub.app.ui.SearchStateSaver
import com.downloadhub.app.ui.expressive.model.MAIN_QUEUE_ID
import com.downloadhub.app.ui.expressive.model.linkSafeToSave
import com.downloadhub.app.ui.expressive.model.resolveQueueId
import com.downloadhub.core.SearchGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreAndQueueTest {
    private val existing = listOf("main", "night", "work")

    @Test fun mainAndNullMeanTheMainQueue() {
        assertNull(resolveQueueId(null, existing).id)
        assertNull(resolveQueueId(MAIN_QUEUE_ID, existing).id)
        assertFalse(resolveQueueId(null, existing).fellBack)
    }

    @Test fun anExistingQueueIsKept() {
        val r = resolveQueueId("night", existing)
        assertEquals("night", r.id)
        assertFalse(r.fellBack)
    }

    @Test fun aDeletedQueueFallsBackToMainAndSaysSo() {
        val r = resolveQueueId("gone", existing)
        assertNull(r.id)
        assertTrue(r.fellBack)
    }

    @Test fun plainLinksAreSaved() {
        assertTrue(linkSafeToSave("https://example.com/file.zip"))
        assertTrue(linkSafeToSave("magnet:?xt=urn:btih:abc&dn=Sintel"))
        assertTrue(linkSafeToSave("https://example.com/a?page=2&sort=new"))
    }

    @Test fun linksWithALoginAreNotSaved() {
        assertFalse(linkSafeToSave("https://user:pass@example.com/file.zip"))
        assertFalse(linkSafeToSave("ftp://me@host/file"))
    }

    @Test fun signedOrTokenLinksAreNotSaved() {
        assertFalse(linkSafeToSave("https://x.com/f?token=abc"))
        assertFalse(linkSafeToSave("https://x.com/f?a=1&X-Amz-Signature=abc"))
        assertFalse(linkSafeToSave("https://x.com/f?a=1&API_KEY=abc"))
    }

    @Test fun anAtSignLaterInThePathIsNotALogin() {
        assertTrue(linkSafeToSave("https://example.com/users/@me/file"))
    }

    @Test fun searchStateSurvivesTheSaver() {
        val state = SearchScreenState().apply {
            query = "sintel"
            group = SearchGroup.entries.last()
        }
        val scope = SaverScope { true }
        val saved = with(SearchStateSaver) { scope.save(state) }!!
        val back = SearchStateSaver.restore(saved)!!
        assertEquals("sintel", back.query)
        assertEquals(SearchGroup.entries.last(), back.group)
    }

    @Test fun anUnknownSavedGroupFallsBackToMovies() {
        val back = SearchStateSaver.restore(listOf("q", "NO_SUCH_GROUP"))!!
        assertEquals("q", back.query)
        assertEquals(SearchGroup.MOVIES, back.group)
    }
}
