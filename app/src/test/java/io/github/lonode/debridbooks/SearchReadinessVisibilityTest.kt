package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test

class SearchReadinessVisibilityTest {
    private val results=(1..18).map { SearchItem("$it","Book $it",cached=it==1,format=if(it==18) "AAC" else "MP3") }

    @Test fun `account-ready filtering reports the one visible result and seventeen hidden results without removing them`() {
        val shown=visibleSearchResults(results,true)
        assertEquals(listOf("1"),shown.map{it.id})
        assertEquals("1 shown · 18 loaded · 17 hidden by filters",searchCountLabel(results.size,shown.size))
        assertEquals(18,visibleSearchResults(results,false).size)
        assertEquals("18 results loaded",searchCountLabel(18,18))
    }
    @Test fun `format and readiness use the same visible subset for the list and its count`() {
        assertEquals(1,visibleSearchResults(results,false,"aac").size)
        val shown=visibleSearchResults(results,true,"AAC")
        assertTrue(shown.isEmpty())
        assertEquals("0 shown · 18 loaded · 18 hidden by filters",searchCountLabel(18,shown.size))
        assertEquals(17,visibleSearchResults(results,false,"mp3").size)
    }
    @Test fun `explicit provider readiness updates only the selected upload without claiming other uploads are uncached`() {
        val session=SearchSession().requesting(SearchRequest("divergente",refresh=true)).received(SearchPage(results))
        val updated=session.confirmedReady("2")
        assertEquals(listOf("1","2"),visibleSearchResults(updated.results,true).map{it.id})
        assertEquals(18,updated.results.size)
        assertEquals(session.results.drop(2),updated.results.drop(2))
        assertEquals(session.pages.keys,updated.pages.keys)
        assertEquals(session.query,updated.query)
        assertEquals(1,visibleSearchResults(session.results,true).size)
    }
}
