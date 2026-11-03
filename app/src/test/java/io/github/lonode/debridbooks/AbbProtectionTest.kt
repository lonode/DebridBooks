package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AbbProtectionTest {
    // Keep these provider-specific fixtures isolated from automatic public discovery.
    private val testedSources = setOf("C411", "Tr4ker", "YggReborn", "Jackett", "AudiobookBay")
    private val url = "https://abb.invalid/?s=book"
    private fun ok() = DirectResponse(200,"<p>Public page</p>".toByteArray())

    @Test fun `cache coalesces concurrent identical requests and survives disk reload with separate detail TTL`() = runBlocking {
        val file = File.createTempFile("abb-cache", ".json").apply { delete() }
        try {
            var now=10000L; var calls=0
            val http=DirectTransport { calls++; delay(20); ok() }
            val store=FileAbbStore(file)
            val access=AbbAccess(http,store,{now},{now+=it})
            coroutineScope { List(8) { async { access.get(url) } }.awaitAll() }
            assertEquals(1,calls)
            val restored=AbbAccess(http,FileAbbStore(file),{now},{now+=it})
            assertTrue(restored.get(url).cached); assertEquals(1,calls)
            restored.get("https://abb.invalid/abss/book/",detail=true); assertEquals(2,calls)
            now+=1800001
            restored.get(url); assertEquals(3,calls)
            assertTrue(restored.get("https://abb.invalid/abss/book/",detail=true).cached); assertEquals(3,calls)
            assertFalse(file.readText().contains("synthetic-provider"))
        } finally { file.delete(); File(file.path+".bak").delete() }
    }

    @Test fun `queued searches stop after a block cooldown survives restart and force grants one paced attempt`() = runBlocking {
        var now=10000L; val starts=mutableListOf<Long>(); val store=MemoryAbbStore()
        val http=DirectTransport { starts+=now; DirectResponse(429,byteArrayOf(),retryAfter="7200") }
        val access=AbbAccess(http,store,{now},{now+=it})
        coroutineScope { List(5) { i -> async { runCatching { access.get("$url$i") } } }.awaitAll() }
        assertEquals(1,starts.size)
        val restored=AbbAccess(http,store,{now},{now+=it})
        val paused=runCatching { restored.get(url) }.exceptionOrNull() as DirectFailure
        assertEquals("COOLDOWN",paused.reason); assertTrue(paused.retryAt!!>=7210000)
        val forced=runCatching { restored.get(url,force=true) }.exceptionOrNull() as DirectFailure
        assertEquals("LIMIT",forced.reason); assertEquals(2,starts.size); assertTrue(starts[1]-starts[0]>=5000)
        assertTrue(forced.retryAt!!>=paused.retryAt!!)
        runCatching { restored.get(url) }; assertEquals(2,starts.size)
        now=forced.retryAt!!+1
        runCatching { restored.get(url) }; assertEquals(3,starts.size)
    }

    @Test fun `cancelled paced request sends nothing and stale cache is labelled while paused`() = runBlocking {
        var now=10000L; var calls=0; val store=MemoryAbbStore()
        val http=DirectTransport { calls++; if(calls==1) ok() else throw DirectFailure("Service","TIMEOUT") }
        val access=AbbAccess(http,store,{now},{now+=it})
        access.get(url); now+=1800001
        val stale=access.get(url)
        assertTrue(stale.cached); assertNotNull(stale.cooldownUntil); assertEquals(2,calls)
        assertTrue(access.get(url).cached); assertEquals(2,calls)
        val waiting=CompletableDeferred<Unit>()
        val paused=AbbAccess(http,store,{now},{waiting.complete(Unit); awaitCancellation()})
        // Force retains the cooldown while waiting for the same global pacing boundary.
        val job=launch { paused.get(url,force=true) }
        withTimeout(2000) { waiting.await() };job.cancelAndJoin()
        assertEquals(2,calls)
        assertEquals("COOLDOWN",(runCatching { paused.get("${url}2") }.exceptionOrNull() as DirectFailure).reason)
    }

    @Test fun `targeted force retry merges source results and cannot silently repeat its force permission`() {
        val other=SearchItem("tracker","Tracker",tracker="C411",searchSource="C411")
        val abb=SearchItem("abb","ABB",tracker="AudiobookBay",searchSource="AudiobookBay")
        var session=SearchSession().requesting(SearchRequest("book",refresh=true)).received(SearchPage(listOf(other,abb)))
        val force=SearchRequest("book",sources=setOf("AudiobookBay"),forceAbb=true)
        session=session.requesting(force).received(SearchPage(listOf(abb.copy(title="Updated"))))
        assertEquals(listOf("tracker","abb"),session.results.map{it.id})
        val cancelled=session.cancelled()
        assertFalse(cancelled.retry!!.forceAbb);assertEquals(setOf("AudiobookBay"),cancelled.retry!!.sources)
        val restored=ResolverItem.decode(ResolverItem(n="Book",a="https://abb.invalid/abss/book/").encode())
        assertNotNull(restored.a)
        assertEquals("a".repeat(40),ResolverItem.decode(ResolverItem(h="a".repeat(40),n="Old book").encode()).h)
    }
    @Test fun `search fetches one page selected resolution fetches one detail and cached hash cannot change library identity`() = runBlocking {
        var now=10000L;val calls=mutableListOf<String>();val hash="a".repeat(40)
        val http=DirectTransport { request ->
            calls+=request.url
            val text=when {
                java.net.URI(request.url).host=="api.alldebrid.com" -> """{"status":"success","data":{"magnets":[{"hash":"$hash","statusCode":4}]}}"""
                java.net.URI(request.url).path=="/abss/book/" -> "Info Hash: $hash <p>Language: English</p><p>Format: M4B / Bitrate: 64 Kbps</p>"
                else -> """<a href="/abss/book/" rel="bookmark">Book</a><p>Language: English</p><p>Format: M4B / Bitrate: 64 Kbps</p>"""
            }
            DirectResponse(200,text.toByteArray())
        }
        val cfg=StandaloneConfig("synthetic",abbDomain="abb.invalid",languageFilter="en")
        val search=TorrentSearch(cfg,http,AbbAccess(http,MemoryAbbStore(),{now},{now+=it}))
        val first=search.search("Book",1,false, onlySources = testedSources).results.single()
        assertFalse(first.cached);assertEquals("M4B",first.format);assertEquals(1,calls.count { java.net.URI(it).host=="abb.invalid" })
        val resolved=search.resolveAbb(ResolverItem.decode(first.id))
        assertEquals(hash,resolved.h)
        assertEquals(2,calls.count { java.net.URI(it).host=="abb.invalid" })
        val second=search.search("Book",1,false, onlySources = testedSources).results.single()
        assertEquals(first.id,second.id);assertTrue(second.cached)
        assertEquals(2,calls.count { java.net.URI(it).host=="abb.invalid" })
        search.resolveAbb(ResolverItem.decode(second.id));assertEquals(2,calls.count { java.net.URI(it).host=="abb.invalid" })
        assertTrue(runCatching { search.resolveAbb(ResolverItem(n="Bad",a="https://other.invalid/abss/book/")) }.isFailure)
        assertTrue(calls.none { it.contains("upload") || it.contains("unlock") || it.contains("other.invalid") })
    }

    @Test fun `force searches only ABB and honours HTTP date retry boundaries`() = runBlocking {
        var now=10000L;val hosts=mutableListOf<String>()
        val http=DirectTransport { request ->
            val host=java.net.URI(request.url).host;hosts+=host
            assertTrue(host in setOf("abb.invalid","api.alldebrid.com"))
            if(host=="api.alldebrid.com") DirectResponse(200,"""{"status":"success","data":{"magnets":[]}}""".toByteArray())
            else ok()
        }
        val cfg=StandaloneConfig("synthetic",abbDomain="abb.invalid",c411ApiKey="synthetic-tracker")
        TorrentSearch(cfg,http,AbbAccess(http,MemoryAbbStore(),{now},{now+=it})).search("book",1,false,setOf("AudiobookBay"),true)
        assertEquals(1,hosts.count{it=="abb.invalid"})
        val date=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now+3600000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(3600000L,AbbAccess.retryAfterMillis(date,now))
        assertEquals(900000000L,AbbAccess.retryAfterMillis("900000",now))
        assertNull(AbbAccess.retryAfterMillis("invalid",now))
    }

}
