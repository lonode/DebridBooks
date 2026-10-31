package io.github.lonode.debridbooks
import io.github.lonode.debridbooks.domain.*
import org.junit.Assert.*
import org.junit.Test
class MobileContractTest {
    @Test fun `legacy install links preserve configuration and port`() {
        assertEquals("http://localhost:7000/synthetic_config", InstallLink.parse("http://localhost:7000/synthetic_config/manifest.json").prefix)
        assertEquals("https://example.invalid/synthetic_config", InstallLink.parse("stremio://example.invalid/synthetic_config/manifest.json").prefix)
        assertEquals("https://example.invalid/synthetic_config", InstallLink.parse("https://example.invalid/synthetic_config/configure/").prefix)
    }
    @Test fun `invalid links are rejected without echoing input`() {
        listOf("https://example.invalid", "https://user:secret@example.invalid/config", "file:///config", "https://example.invalid/a/b").forEach {
            assertEquals("Invalid configuration link", assertThrows(IllegalArgumentException::class.java) { InstallLink.parse(it) }.message)
        }
    }
    @Test fun `configured health accepts providers and compatibility alias`() {
        assertEquals("alldebrid", MobileContract.parseCapabilities("""{"ok":true,"addon":"DebridAB","provider":"alldebrid","providerKeyValid":true}""").provider)
        assertEquals("torbox", MobileContract.parseCapabilities("""{"ok":true,"addon":"DebridAB","torboxKeyValid":true}""").provider)
        listOf("{}", """{"ok":true,"addon":"DebridAB"}""", """{"ok":true,"addon":"DebridAB","providerKeyValid":false,"torboxKeyValid":true}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { MobileContract.parseCapabilities(it) }
        }
    }
    @Test fun `legacy results accept long resolver IDs without persisting them as local keys`() {
        val id = "x".repeat(500)
        val page = MobileContract.parseSearch("""{"results":[{"id":"$id","title":"Example"}]}""")
        assertEquals(id, page.results.single().id)
        assertEquals(64, MobileContract.localId(id).length)
        assertThrows(IllegalArgumentException::class.java) { MobileContract.parseSearch("""{"items":[]}""") }
    }
}
