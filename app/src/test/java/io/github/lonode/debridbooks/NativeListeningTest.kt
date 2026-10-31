package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.playback.*
import io.github.lonode.debridbooks.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class NativeListeningTest {
    @Test fun `listening excludes seek distance buffering and pauses and respects speed`() {
        val clock = ListeningClock()
        val wall = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        assertTrue(clock.sample("a","b","p",0,true,2f,0,wall).isEmpty())
        assertEquals(1000L, clock.sample("a","b","p",2000,true,2f,1000,wall+1000).sumOf { it.second })
        assertTrue(clock.sample("a","b","p",200000,true,2f,2000,wall+2000).isEmpty())
        assertEquals(1000L, clock.sample("a","b","p",202000,false,2f,3000,wall+3000).sumOf { it.second })
        assertTrue(clock.sample("a","b","p",202000,false,2f,4000,wall+4000).isEmpty())
        assertTrue(clock.sample("a","other","p",0,true,1f,5000,wall+5000).isEmpty())
    }
    @Test fun `midnight listening is split by local calendar and streaks survive DST`() {
        val zone = ZoneId.of("Europe/Paris")
        val end = LocalDate.of(2026,10,26).atStartOfDay(zone).toInstant().toEpochMilli()+500
        val clock = ListeningClock()
        clock.sample("a","b","p",0,true,1f,0,end-1000,zone)
        assertEquals(listOf("2026-10-25" to 500L, "2026-10-26" to 500L), clock.sample("a","b","p",1000,true,1f,1000,end,zone))
        val summary = listeningSummary(mapOf("2026-10-24" to 60000,"2026-10-25" to 120000),LocalDate.of(2026,10,26))
        assertEquals(2,summary.streak); assertEquals(2,summary.best); assertEquals(180000L,summary.week)
    }
    @Test fun `smart rewind bedtime and explicit chapter time have safe boundaries`() {
        assertEquals(0L,smartRewindMs(10000)); assertEquals(5000L,smartRewindMs(60000)); assertEquals(15000L,smartRewindMs(600000)); assertEquals(30000L,smartRewindMs(7200000))
        assertTrue(inBedtime(23,22)); assertTrue(inBedtime(2,22)); assertFalse(inBedtime(6,22)); assertFalse(inBedtime(14,22))
        assertEquals(5025000L,parsePlaybackTime("1:23:45")); assertNull(parsePlaybackTime("1:99")); assertNull(parsePlaybackTime("-1")); assertNull(parsePlaybackTime("https://example.invalid"))
    }
    @Test fun `upload grouping never merges different volumes languages or authors or changes IDs`() {
        val first=SearchItem("one","Livre 1",author="Alice",language="fr")
        val second=first.copy(id="two",format="MP3")
        val all=listOf(first,second,first.copy(id="three",title="Livre 2"),first.copy(id="four",language="en"),first.copy(id="five",author="Bob"))
        val groups=groupUploads(all)
        assertEquals(4,groups.size); assertEquals(listOf("one","two"),groups.first().map { it.id })
    }
}
