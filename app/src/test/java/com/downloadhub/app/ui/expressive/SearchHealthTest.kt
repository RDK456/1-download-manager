package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.searchHealthShape
import com.downloadhub.app.ui.expressive.model.searchSummary
import org.junit.Test
import org.junit.Assert.assertEquals

class SearchHealthTest {
    @Test fun unknownHealthIsNotWarning() = assertEquals(StateShapeKind.CIRCLE, searchHealthShape(false, 0))
    @Test fun seededIsHealthy() = assertEquals(StateShapeKind.COOKIE, searchHealthShape(true, 3))
    @Test fun reportedZeroIsWarning() = assertEquals(StateShapeKind.PENTAGON, searchHealthShape(true, 0))
    @Test fun summaryUsesSingulars() =
        assertEquals(listOf("1 GB", "1 seed · 1 peer", "Archive"), searchSummary("1 GB", true, 1, 1, "Archive"))
    @Test fun summaryDoesNotInventCounts() =
        assertEquals("seeds and peers unknown", searchSummary("1 GB", false, 0, 0, "x")[1])
}
