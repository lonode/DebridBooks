package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.ui.*
import org.junit.Assert.*
import org.junit.Test

class LibraryShelfTest {
    private fun book(id: String, updated: Long, saved: Boolean = false, download: String? = null, completed: Boolean = false, part: Int = 0, position: Long = 6000) = ShelfBook(
        BookEntity("account",id,id,null,1),ProgressEntity("account",id,"part",part,position,1f,completed,updated),
        BookExtras(saved=saved),download?.let { AudioDownload("account",id,it) })

    @Test fun `saved and downloaded books keep their place in continue listening`() {
        val saved=book("saved",10,saved=true)
        val offline=book("offline",20,saved=true,download="COMPLETE")
        val finished=book("finished",30,completed=true)
        val books=listOf(saved,offline,finished)
        assertEquals(offline,continueBook(books))
        assertEquals(listOf(saved,offline),shelfBooks(books,"progress"))
        assertEquals(listOf(saved,offline),shelfBooks(books,"saved"))
        assertEquals(listOf(offline),shelfBooks(books,"offline"))
        assertEquals(listOf(finished),shelfBooks(books,"finished"))
        assertEquals(books,shelfBooks(books,"all"))
    }

    @Test fun `later part at zero remains resumable and incomplete downloads are not offline`() {
        val later=book("later",20,part=4,position=0,download="PAUSED")
        val unstarted=book("new",30,position=0,download="RUNNING")
        assertEquals(later,continueBook(listOf(later,unstarted)))
        assertTrue(shelfBooks(listOf(later,unstarted),"offline").isEmpty())
        assertNull(continueBook(listOf(unstarted)))
    }

    @Test fun `continue selection is independent of shelf order and retains account identity`() {
        val older=book("same",10)
        val newer=older.copy(book=older.book.copy(accountId="other"),progress=older.progress!!.copy(accountId="other",updatedAt=20))
        assertEquals(newer,continueBook(listOf(newer,older)))
        assertEquals(newer,continueBook(listOf(older,newer)))
        assertEquals(listOf(newer,older),shelfBooks(listOf(newer,older),"progress"))
    }
}
