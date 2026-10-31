package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticSearchTest {
    private val first=SearchPage(listOf(SearchItem("one","First")))
    private fun loaded()=SearchSession().requesting(SearchRequest("book",refresh=true)).received(first)

    @Test fun `automatic pages stop on empty or repeated results instead of fetching forever`() {
        val first=loaded();assertEquals(SearchRequest("book",2),first.automaticNextRequest())
        val duplicate=first.requesting(first.automaticNextRequest()!!).received(this.first)
        assertNull(duplicate.automaticNextRequest())
        val empty=first.requesting(first.automaticNextRequest()!!).received(SearchPage(emptyList()))
        assertNull(empty.automaticNextRequest())
        val more=first.requesting(first.automaticNextRequest()!!).received(SearchPage(listOf(SearchItem("two","Second"))))
        assertEquals(3,more.automaticNextRequest()!!.number)
    }
    @Test fun `automatic pagination excludes blocked and paused sources without force permission`() {
        val session=loaded().copy(sources=listOf(
            SearchSourceProgress("C411",SearchSourceState.Complete,1),
            SearchSourceProgress("AudiobookBay",SearchSourceState.Failed,issue="COOLDOWN",cooldownUntil=Long.MAX_VALUE),
            SearchSourceProgress("Tr4ker",SearchSourceState.Failed,issue="LIMIT"),
            SearchSourceProgress("AllDebrid",SearchSourceState.Complete),
            SearchSourceProgress("Book details",SearchSourceState.Complete)))
        val next=session.automaticNextRequest()!!
        assertEquals(setOf("C411"),next.sources);assertFalse(next.forceAbb)
        val allFailed=session.copy(sources=session.sources.filter { it.source!="C411" })
        assertNull(allFailed.automaticNextRequest())
    }
    @Test fun `cancel and failure require an explicit retry rather than restarting at the visible footer`() {
        assertNull(loaded().cancelled().automaticNextRequest())
        assertNull(loaded().failed(IllegalStateException()).automaticNextRequest())
        assertNull(SearchSession().automaticNextRequest())
        val capped=SearchSession(query="book",pages=mapOf(10000 to first))
        assertNull(capped.automaticNextRequest())
    }
    @Test fun `ABB timeout warns about a possible ban without asserting it as the cause`() {
        val label=SearchWarning("AudiobookBay","TIMEOUT","").displayWarning()
        assertTrue(label.contains("You may be banned"));assertTrue(label.contains("unreachable"))
        assertFalse(SearchWarning("C411","TIMEOUT","").displayWarning().contains("banned"))
    }
}
