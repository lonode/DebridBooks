package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test

class SearchSourceCountTest {
    private val book = SearchItem("one", "Les Misérables", tracker = "Tr4ker", searchSource = "Tr4ker")
    private fun loaded() = SearchSession().requesting(SearchRequest("monseigneur", refresh = true))
        .progressing(SearchProgress(SearchPage(listOf(book)), listOf(SearchSourceProgress("Tr4ker", SearchSourceState.Complete, 1)), true))

    @Test fun `empty automatic next page does not erase the earlier source total`() {
        val first = loaded()
        val empty = SearchProgress(SearchPage(emptyList()), listOf(SearchSourceProgress("Tr4ker", SearchSourceState.Complete, 0)), true)
        val final = first.requesting(first.automaticNextRequest()!!).progressing(empty).received(empty.page)
        assertEquals(0, final.sources.single().count)
        assertEquals(listOf(book), final.results)
        assertEquals(1, final.loadedFrom("Tr4ker"))
        assertEquals(0, final.loadedFrom("C411"))
        assertNull(final.automaticNextRequest())
    }

    @Test fun `duplicate pages do not inflate totals and retry replaces the source contribution`() {
        val first = loaded()
        val repeated = first.requesting(SearchRequest("monseigneur", 2)).received(SearchPage(listOf(book)))
        assertEquals(1, repeated.loadedFrom("Tr4ker"))
        val replaced = first.requesting(SearchRequest("monseigneur", sources = setOf("Tr4ker")))
            .received(SearchPage(emptyList()))
        assertEquals(0, replaced.loadedFrom("Tr4ker"))
        assertTrue(replaced.results.isEmpty())
    }

    @Test fun `retained results keep their origin through failure cancellation and a new query`() {
        val first = loaded()
        assertEquals(1, first.requesting(SearchRequest("monseigneur", 2)).failed(IllegalStateException()).loadedFrom("Tr4ker"))
        assertEquals(1, first.requesting(SearchRequest("monseigneur", 2)).cancelled().loadedFrom("Tr4ker"))
        val jackett = SearchItem("two", "Other book", tracker = "Custom tracker", searchSource = "Jackett")
        val next = first.requesting(SearchRequest("another", refresh = true)).received(SearchPage(listOf(jackett)))
        assertEquals(0, next.loadedFrom("Tr4ker"))
        assertEquals(1, next.loadedFrom("Jackett"))
        assertEquals(0, next.loadedFrom("Custom tracker"))
    }
}
