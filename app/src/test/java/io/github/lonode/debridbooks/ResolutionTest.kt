package io.github.lonode.debridbooks
import io.github.lonode.debridbooks.network.MobileApi
import org.junit.Assert.*
import org.junit.Test
class ResolutionTest {
    @Test fun `preparing is distinct from invalid ready queue`() {
        assertFalse(MobileApi.parseResolution("""{"ready":false,"streams":[]}""").ready)
        assertThrows(IllegalArgumentException::class.java) { MobileApi.parseResolution("""{"ready":true,"streams":[]}""") }
    }
    @Test fun `identity survives URL renewal and ambiguous filenames are rejected`() {
        val part = """{"title":"Part 1","filename":"part1.m4b","url":"http://localhost:7000/synthetic/app/audio/example"}"""
        val body = """{"ready":true,"streams":[$part]}"""
        assertEquals(MobileApi.parseResolution(body).streams.single().id, MobileApi.parseResolution(body.replace("/example", "/renewed")).streams.single().id)
        assertThrows(IllegalArgumentException::class.java) { MobileApi.parseResolution("""{"ready":true,"streams":[$part,$part]}""") }
        assertThrows(IllegalArgumentException::class.java) { MobileApi.parseResolution(body.replace("http:", "file:")) }
    }
}
