package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test

class SearchCancellationTest {
    private val first=SearchPage(listOf(SearchItem("one","First result",cached=true)))
    private fun loaded()=SearchSession().requesting(SearchRequest("book",refresh=true)).received(first)

    @Test fun `cancel preserves successful pages and retry targets the interrupted next page`() {
        val request=SearchRequest("book",2)
        val cancelled=loaded().requesting(request).cancelled()
        assertEquals(first.results,cancelled.results)
        assertEquals(2,cancelled.nextPage)
        assertEquals(request,cancelled.retry)
        assertTrue(cancelled.feedbackMessage!!.contains("cancelled"))
        assertNull(cancelled.failure)
        val retried=cancelled.requesting(cancelled.retry!!).received(SearchPage(listOf(SearchItem("two","Second result"))))
        assertEquals(listOf("one","two"),retried.results.map { it.id })
        assertFalse(retried.wasCancelled)
    }
    @Test fun `cancelled replacement query keeps previous results until an explicit retry succeeds`() {
        val request=SearchRequest("different book",refresh=true)
        val cancelled=loaded().requesting(request).cancelled()
        assertEquals("book",cancelled.query)
        assertEquals(first.results,cancelled.results)
        assertEquals(request,cancelled.retry)
        val retried=cancelled.requesting(request).received(SearchPage(listOf(SearchItem("new","Replacement"))))
        assertEquals("different book",retried.query)
        assertEquals(listOf("new"),retried.results.map { it.id })
        assertEquals(1,retried.pages.size)
    }
    @Test fun `late cancelled responses and errors cannot repopulate or replace search results`() {
        val cancelled=loaded().requesting(SearchRequest("book",2)).cancelled()
        assertSame(cancelled,cancelled.received(SearchPage(listOf(SearchItem("late","Late response")))))
        assertSame(cancelled,cancelled.failed(IllegalStateException("Late failure")))
        val empty=SearchSession().requesting(SearchRequest("new")).cancelled()
        assertTrue(empty.results.isEmpty())
        assertEquals("Search cancelled.",empty.feedbackMessage)
        assertEquals(SearchRequest("new"),empty.retry)
    }
}
