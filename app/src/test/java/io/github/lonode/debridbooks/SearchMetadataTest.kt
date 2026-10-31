package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test

class SearchMetadataTest {
    @Test fun `cards show server metadata without inventing unknown language or tracker`() {
        val page = MobileContract.parseSearch("""{"results":[{"id":"synthetic","title":"A book","language":"fr","tracker":"YggReborn","sizeText":"200 MB","releaseName":"A.book.m4b","cached":true}]}""")
        assertEquals(listOf("French", "200 MB", "M4B", "YggReborn"), page.results.single().displayMetadata())
        assertEquals(listOf("MangaDex"), SearchItem("series", "Manga", provider = "mangadex", format = "MangaDex").displayMetadata())
        assertTrue(page.results.single().cached)
        assertTrue(SearchItem("synthetic", "Title FR from unverified tracker").displayMetadata().isEmpty())
    }
}
