package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import kotlinx.coroutines.*
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class OpeningProgressTest {
    private val hash="a".repeat(40)
    private fun response(data: String) = DirectResponse(200,"""{"status":"success","data":$data}""".toByteArray())
    private fun transport(unlock: suspend () -> DirectResponse) = DirectTransport { request ->
        when(URI(request.url).path) {
            "/v4.1/magnet/status" -> response("""{"magnets":[{"id":42,"hash":"$hash","statusCode":4}]}""")
            "/v4/magnet/files" -> response("""{"magnets":[{"id":42,"files":[{"n":"secret-folder/10.mp3","l":"https://alldebrid.com/f/ten"},{"n":"secret-folder/2.mp3","l":"https://alldebrid.com/f/two"},{"n":"cover.jpg","l":"https://alldebrid.com/f/cover"}]}]}""")
            "/v4/link/unlock" -> unlock()
            else -> error("Must not upload or contact trackers for an existing transfer")
        }
    }
    @Test fun `resolution reports real file completion without changing order or exposing private data`() = runBlocking {
        val events=mutableListOf<OpeningProgress>();var calls=0
        val result=AllDebridClient(StandaloneConfig("synthetic-secret"),transport {
            val before=events.last()
            assertEquals(OpeningStage.PreparingFiles,before.stage)
            assertEquals(calls,before.completed)
            assertEquals(2,before.total)
            calls++;response("""{"link":"https://cdn.invalid/signed-secret-$calls"}""")
        },MemoryMagnetRegistry()).resolve(ResolverItem(hash,n="Book"),events::add)
        assertTrue(result.ready)
        assertEquals(listOf("secret-folder/2.mp3","secret-folder/10.mp3"),result.streams.map { it.filename })
        assertEquals(listOf(OpeningStage.WaitingForProvider,OpeningStage.CheckingAccount,OpeningStage.CheckingTransfer,OpeningStage.ReadingFiles),events.take(4).map { it.stage })
        assertEquals(listOf(0,1,2),events.filter { it.stage==OpeningStage.PreparingFiles }.map { it.completed })
        assertTrue(events.zipWithNext().all { (a,b) -> b.updatedAtNanos>=a.updatedAtNanos })
        assertFalse(events.toString().contains("secret"));assertFalse(events.toString().contains("https"));assertFalse(events.toString().contains(hash))
    }
    @Test fun `cancelling a stalled unlock never reports that file as completed`() = runBlocking {
        val events=mutableListOf<OpeningProgress>();val entered=CompletableDeferred<Unit>()
        val client=AllDebridClient(StandaloneConfig("synthetic-secret"),transport {
            entered.complete(Unit);awaitCancellation()
        },MemoryMagnetRegistry())
        val job=launch { client.resolve(ResolverItem(hash,n="Book"),events::add) }
        withTimeout(5000){entered.await()}
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(OpeningStage.PreparingFiles,events.last().stage)
        assertEquals(0,events.last().completed)
        // The resolver lock is released after cancellation, so the next open can proceed.
        val result=AllDebridClient(StandaloneConfig("synthetic-secret"),transport {
            response("""{"link":"https://cdn.invalid/ready"}""")
        },MemoryMagnetRegistry()).resolve(ResolverItem(hash,n="Book"))
        assertTrue(result.ready)
    }
    @Test fun `preparing transfers report readiness without fabricating file progress`() = runBlocking {
        val events=mutableListOf<OpeningProgress>()
        val client=AllDebridClient(StandaloneConfig("key"),DirectTransport {
            response("""{"magnets":[{"id":42,"hash":"$hash","statusCode":1}]}""")
        },MemoryMagnetRegistry())
        assertFalse(client.resolve(ResolverItem(hash,n="Book"),events::add).ready)
        assertEquals(OpeningStage.CheckingTransfer,events.last().stage)
        assertTrue(events.all { it.total==null && it.completed==0 })
    }
    @Test fun `elapsed time is monotonic whole seconds and invalid file counts are rejected`() {
        assertEquals(0L,openingSeconds(2_000_000_000,1_000_000_000))
        assertEquals(7L,openingSeconds(1_000_000_000,8_999_999_999))
        assertEquals(8L,openingSeconds(1_000_000_000,9_000_000_000))
        assertTrue(runCatching { OpeningProgress(OpeningStage.PreparingFiles,2,1) }.isFailure)
        assertTrue(runCatching { OpeningProgress(OpeningStage.PreparingFiles,-1,2) }.isFailure)
    }
}
