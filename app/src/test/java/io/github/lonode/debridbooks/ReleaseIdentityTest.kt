package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.standalone.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReleaseIdentityTest {
    @Test fun `catalogue confirmation cannot erase a year inside a work title`() = runBlocking {
        val http = DirectTransport { DirectResponse(200, """{"results":[{"collectionName":"Paris","artistName":"Verified Writer","artworkUrl100":"https://images.invalid/paris.jpg"}]}""".toByteArray()) }
        val literal = CatalogClient(http).enrich(SearchItem("literal", "Paris 1919"))
        assertEquals("Paris 1919", literal.title)
        assertNull(literal.author)
        assertNull(literal.poster)
        val authored = CatalogClient(http).enrich(SearchItem("authored", "Verified Writer - Paris 1919"))
        assertEquals("Paris 1919", authored.title)
        assertEquals("Verified Writer", authored.author)
        assertNull(authored.poster)
    }

    @Test fun `a date outside a catalogue verified trailing author does not become part of the title`() = runBlocking {
        val http = DirectTransport { DirectResponse(200, """{"results":[{"collectionName":"Paris 1919","artistName":"Another Verified Writer","artworkUrl100":"https://images.invalid/paris1919.jpg"}]}""".toByteArray()) }
        val raw = "Paris 1919 Another Verified Writer 2024 [MP3]"
        assertEquals("Paris 1919 Another Verified Writer 2024", ReleaseName.parse(raw).title)
        val item = SearchItem("opaque-unchanged", raw, releaseName = raw)
        val actual = CatalogClient(http).enrich(item)
        assertEquals("Paris 1919", actual.title)
        assertEquals("Another Verified Writer", actual.author)
        assertEquals(item.id, actual.id)
        assertEquals(raw, actual.releaseName)
    }

    @Test fun `catalog identity corrects author first author last and joined release names`() {
        listOf(
            "Moby.Dick.Herman.Melville.2004.FR.[MP3.192kbps]-NOTAG",
            "Herman Melville - Moby Dick - Lectrice benevole (64 kbits MP3)",
        ).forEach { raw -> assertEquals(raw, ReleaseName("Moby Dick", "Herman Melville"),
            CatalogClient.matchName(ReleaseName.parse(raw), "Moby Dick", "Herman Melville")) }
        assertNull(CatalogClient.matchName(ReleaseName.parse("(pièce radio) Herman Melville - Moby Dick - 1968 [MP3 128kbps]"), "Moby Dick", "Herman Melville"))
        assertEquals(ReleaseName("Moby Dick 1851", "Herman Melville"), CatalogClient.matchName(
            ReleaseName.parse("Herman Melville- Moby Dick 1851 [MP3 - 192kbps]"), "Moby Dick", "Herman Melville"))
        assertEquals(ReleaseName("Lolita [2024]", "Vladimir Nabokov"), CatalogClient.matchName(
            ReleaseName.parse("Vladimir Nabokov - Lolita [2024] [mp3 à 128 kb/s]"), "Lolita", "Vladimir Nabokov"))
        assertEquals(ReleaseName("Code Ardant", "Marge Nantel"), CatalogClient.matchName(
            ReleaseName.parse("Code Ardant Marge Nantel FR -PiXeL"), "Code Ardant", "Marge Nantel"))
        assertEquals(ReleaseName("Harry Potter", "J. K. Rowling"), CatalogClient.matchName(
            ReleaseName.parse("Harry Potter JK Rowling -NOTAG"), "Harry Potter", "J. K. Rowling"))
        assertEquals(ReleaseName("Histoire de la Bible - Un cours particulier", "Olivier Millet"), CatalogClient.matchName(
            ReleaseName.parse("Olivier Millet - Histoire de la Bible - Un cours particulier [mp3 128kbps]"),
            "Histoire de la Bible. Un cours particulier de Olivier Millet", "Olivier Millet"))
        assertEquals(ReleaseName("Frankenstein, l'alchimie de la chair", "Simon Rousseau"), CatalogClient.matchName(
            ReleaseName.parse("Simon Rousseau - Frankenstein, l'alchimie de la chair"),
            "Frankenstein, l'alchimie de la chair: Les classiques interdits (Unabridged)", "Simon Rousseau"))
        assertEquals(ReleaseName("À nos cœurs battants", "Emma Green"), CatalogClient.matchName(
            ReleaseName.parse("A.Nos.Coeurs.Battants.Emma.Green.2024.FR.[MP3.128kbps]-CSSRV"), "À nos cœurs battants", "Emma Green"))
    }

    @Test fun `title resemblance cannot override conflicting author volume or edition`() {
        assertNull(CatalogClient.matchName(ReleaseName.parse("Lolita - Vladimir Nabokov"), "Lolita", "Another Writer"))
        assertNull(CatalogClient.matchName(ReleaseName.parse("Moby Dick - Herman Melville"), "Moby Dick study guide", "Herman Melville"))
        assertNull(CatalogClient.matchName(ReleaseName.parse("Pierre Lemaitre - Au revoir là-haut"), "Au revoir là-haut de Pierre Lemaitre (Analyse d'œuvre)", "lePetitLitteraire"))
        assertNull(CatalogClient.matchName(ReleaseName.parse("Wizard Tome 2 - A Writer"), "Wizard Tome 3", "A Writer"))
        assertNull(CatalogClient.matchName(ReleaseName.parse("Wizard Tome 2 - A Writer"), "Wizard (Book 3)", "A Writer"))
        assertEquals(ReleaseName("Wizard intégrale", "A Writer"), CatalogClient.matchName(ReleaseName.parse("Wizard intégrale - A Writer"), "Wizard", "A Writer"))
        assertEquals(ReleaseName("Madame Bovary & Le Procès de Madame Bovary", "Gustave Flaubert"), CatalogClient.matchName(
            ReleaseName.parse("Gustave Flaubert - Madame Bovary & Le Procès de Madame Bovary"), "Madame Bovary", "Gustave Flaubert"))
        assertEquals(ReleaseName("1984", "George Orwell"), CatalogClient.matchName(ReleaseName.parse("George Orwell - 1984"), "1984", "George Orwell"))
        assertEquals("Harry Potter INTEGRALE", ReleaseName.parse("Harry.Potter.[INTEGRALE].FR.[MP3]").title)
        assertEquals("Skandar and the Chaos Trials Tome 3 par A F Steadman", ReleaseName.parse("Skandar and the Chaos Trials (volume 3) par A.F. Steadman [ENG] [AAX]").title)
        assertEquals("Twenty-One Days", ReleaseName.parse("Twenty-One Days [MP3]").title)
        assertEquals("1984", ReleaseName.parse("1984").title)
    }

    @Test fun `enrichment corrects the displayed identity without changing torrent identity or language`() = runBlocking {
        val raw = "Identity Probe - Blue Horizon [MP3]"
        val original = SearchItem("unchanged-resolver-id", raw, releaseName = raw, language = "en", cached = true)
        val http = DirectTransport { DirectResponse(200, """{"results":[{"collectionName":"Blue Horizon","artistName":"Identity Probe"}]}""".toByteArray()) }
        val result = CatalogClient(http).enrich(original)
        assertEquals("Blue Horizon", result.title)
        assertEquals("Identity Probe", result.author)
        assertEquals(original.id, result.id)
        assertEquals(original.releaseName, result.releaseName)
        assertEquals(original.language, result.language)
        assertEquals(original.cached, result.cached)
    }

    @Test fun `competing authors for the same title leave the unverified author unset`() = runBlocking {
        val http = DirectTransport { request ->
            val body = when {
                request.url.contains("itunes.apple.com") -> """{"results":[{"collectionName":"Ambiguous Benchmark Work","artistName":"One Writer"},{"collectionName":"Ambiguous Benchmark Work","artistName":"Other Writer"}]}"""
                request.url.contains("googleapis.com") -> """{"items":[]}"""
                else -> """{"docs":[]}"""
            }
            DirectResponse(200, body.toByteArray())
        }
        val result = CatalogClient(http).enrich(SearchItem("opaque", "Ambiguous Benchmark Work"))
        assertEquals("Ambiguous Benchmark Work", result.title)
        assertNull(result.author)
    }

    @Test fun `verified covered identity is reused across recording variants without repeating catalog requests`() = runBlocking {
        var calls = 0
        val http = DirectTransport {
            calls++
            DirectResponse(200, """{"results":[{"collectionName":"Distinct Cache Work","artistName":"Cache Writer","artworkUrl100":"https://images.invalid/cache.jpg"}]}""".toByteArray())
        }
        val first = CatalogClient(http).enrich(SearchItem("first", "Cache Writer - Distinct Cache Work [MP3]"))
        val next = CatalogClient(http).enrich(SearchItem("next", "Distinct.Cache.Work.Cache.Writer.2024.FR.[M4B]-NOTAG"))
        assertEquals(1, calls)
        assertEquals(first.title, next.title)
        assertEquals(first.author, next.author)
        assertEquals("next", next.id)
    }

    @Test fun `a failed catalog leaves ambiguous release names intact and does not cache failure for an hour`() = runBlocking {
        val unavailable = DirectTransport { throw DirectFailure("Catalog") }
        val item = SearchItem("opaque", "Uncertain Writer - Uncertain Work")
        val fallback = CatalogClient(unavailable).enrich(item)
        assertEquals("Uncertain Writer - Uncertain Work", fallback.title)
        assertNull(fallback.author)
        val recovered = CatalogClient(DirectTransport {
            DirectResponse(200, """{"results":[{"collectionName":"Uncertain Work","artistName":"Uncertain Writer"}]}""".toByteArray())
        }).enrich(item)
        assertEquals("Uncertain Work", recovered.title)
        assertEquals("Uncertain Writer", recovered.author)
    }

    @Test fun `rate limited catalog falls through to another catalog and respects cooldown across clients`() = runBlocking {
        var booksRequests = 0
        var libraryRequests = 0
        val http = DirectTransport { request -> when {
            request.url.contains("itunes.apple.com") -> DirectResponse(200, """{"results":[]}""".toByteArray())
            request.url.contains("audible.") -> DirectResponse(200, """{"products":[]}""".toByteArray())
            request.url.contains("googleapis.com") -> { booksRequests++; DirectResponse(429, "{}".toByteArray(), retryAfter = "600") }
            else -> { libraryRequests++; DirectResponse(200, """{"docs":[{"title":"Fallback Catalog Work","author_name":["Fallback Writer"]}]}""".toByteArray()) }
        } }
        val first = CatalogClient(http).enrich(SearchItem("first", "Fallback Writer - Fallback Catalog Work"))
        assertEquals("Fallback Catalog Work", first.title)
        assertEquals("Fallback Writer", first.author)
        // A separate work prevents positive metadata caching from masking the rate-limit check.
        CatalogClient(http).enrich(SearchItem("second", "Uncached Catalog Work"))
        assertEquals(1, booksRequests)
        assertEquals(2, libraryRequests)
    }

    @Test fun `original language catalog title can identify a separated author without replacing the release`() = runBlocking {
        val http = DirectTransport { request ->
            val body = when {
                request.url.contains("itunes.apple.com") -> """{"results":[]}"""
                request.url.contains("googleapis.com") -> """{"items":[]}"""
                else -> """{"docs":[{"title":"Brandvägg","author_name":["Henning Mankell"],"cover_i":805246}]}"""
            }
            DirectResponse(200, body.toByteArray())
        }
        val result = CatalogClient(http).enrich(SearchItem("opaque", "Henning Mankell - La muraille invisible"))
        assertEquals("La muraille invisible", result.title)
        assertEquals("Henning Mankell", result.author)
        assertNull(result.poster)
    }

    @Test fun `garbled feed accents are repaired for metadata while the original resolver remains intact`() = runBlocking {
        val raw = "\u00c3\u0089ric-Emmanuel Schmitt - F\u00c3\u00a9lix et la source invisible [2019] [256kbps]"
        val item = SearchItem("unchanged-opaque-id", raw, releaseName = raw, language = "fr")
        val http = DirectTransport {
            DirectResponse(200, """{"results":[{"collectionName":"Félix et la source invisible","artistName":"Éric-Emmanuel Schmitt"}]}""".toByteArray())
        }
        val result = CatalogClient(http).enrich(item)
        assertEquals("Félix et la source invisible [2019]", result.title)
        assertEquals("Éric-Emmanuel Schmitt", result.author)
        assertEquals(item.id, result.id)
        assertEquals(raw, result.releaseName)
        assertEquals("Âge", ReleaseName.parse("Âge [MP3]").title)
    }

    @Test fun `audiobook catalog fallback supplies metadata when the first catalog is empty`() = runBlocking {
        val http = DirectTransport { request -> when {
            request.url.contains("itunes.apple.com") -> DirectResponse(200, """{"results":[]}""".toByteArray())
            request.url.contains("api.audible.fr") -> DirectResponse(200, """{"products":[{"asin":"B000000321","title":"French Fallback Work","language":"french","authors":[{"name":"Distinct French Writer"}]}]}""".toByteArray())
            else -> error("Unexpected catalog")
        } }
        val result = CatalogClient(http).enrich(SearchItem("opaque", "Distinct French Writer - French Fallback Work [MP3]", language = "fr"))
        assertEquals("French Fallback Work", result.title)
        assertEquals("Distinct French Writer", result.author)
    }

    @Test fun `explicit translator credits are not confused with the book author`() = runBlocking {
        val http = DirectTransport { request -> if (request.url.contains("itunes.apple.com"))
            DirectResponse(200, """{"results":[]}""".toByteArray()) else
            DirectResponse(200, """{"products":[{"asin":"B0C4TPVMJ3","title":"Un palais de colère et de brume","language":"french","authors":[{"name":"Sarah J. Maas"},{"name":"Anne-Judith Descombey - traductrice"}]}]}""".toByteArray())
        }
        val result = CatalogClient(http).enrich(SearchItem("opaque", "Sarah J. Maas - Un palais de colère et de brume", language = "fr"))
        assertEquals("Sarah J. Maas", result.author)
        assertEquals("Un palais de colère et de brume", result.title)
    }
}
