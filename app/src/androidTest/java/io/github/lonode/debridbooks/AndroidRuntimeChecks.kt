package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.os.Bundle
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.standalone.CatalogClient
import io.github.lonode.debridbooks.network.standalone.DirectResponse
import io.github.lonode.debridbooks.network.standalone.DirectTransport
import io.github.lonode.debridbooks.network.standalone.ResolverItem
import io.github.lonode.debridbooks.network.standalone.StandaloneApi
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import java.net.URI
import kotlinx.coroutines.runBlocking

/** Android runtime checks. Only synthetic fixtures and loopback HTTP; no saved account access. */
class AndroidRuntimeChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val status = Bundle().apply {
            putString("id", "DebridBooksRuntime")
            putInt("numtests", 1)
            putInt("current", 1)
            putString("class", AndroidRuntimeChecks::class.java.name)
            putString("test", "directSearchAndMetadata")
        }
        sendStatus(1, status)
        var stage = "title fixtures"
        try {
            runBlocking {
                val emptyCatalog = DirectTransport { DirectResponse(200, "{}".toByteArray()) }
                val catalog = CatalogClient(emptyCatalog)
                val cases = mapOf("Example [MP3] (retail) {edition}" to "Example {edition}", "Example.FR.MP3" to "Example", "Example_EN-M4B" to "Example",
                    "The English Patient [MP3]" to "The English Patient", "Harry.Potter.J.K.Rowling-NOTAG" to "Harry Potter J K Rowling",
                    "Impact Winter Tome 2 French EditionTravis Beacham [MP3]" to "Impact Winter Tome 2 French EditionTravis Beacham",
                    "The Complete Stories" to "The Complete Stories", "Paris 1919" to "Paris 1919",
                    "History of US Steel" to "History of US Steel", "FRENCH LESSONS" to "FRENCH LESSONS",
                    "A Journey (Volume II) [MP3]" to "A Journey (Volume II)")
                for ((index, entry) in cases.entries.withIndex()) {
                    val (release, expected) = entry
                    stage = "title fixture $index"
                    val item = SearchItem(ResolverItem(n = release).encode(), release, releaseName = release)
                    val enriched = catalog.enrich(item)
                    check(enriched.title == expected)
                    check(enriched.id == item.id && enriched.releaseName == release)
                }
                stage = "XML search and metadata enrichment"
                val http = DirectTransport { request ->
                    val body = when (URI(request.url).host) {
                        "tracker.invalid" -> """<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><item><title>Android Example [MP3] {edition}</title><torznab:attr name="infohash" value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/></item></channel></rss>"""
                        "api.alldebrid.com" -> """{"status":"success","data":{"magnets":[]}}"""
                        else -> "{}"
                    }
                    check(!URI(request.url).path.contains("upload") && !URI(request.url).path.contains("unlock"))
                    DirectResponse(200, body.toByteArray())
                }
                val page = StandaloneApi(StandaloneConfig("synthetic-key", abbDomain = "", c411ApiKey = "synthetic-source", c411Url = "https://tracker.invalid"), http)
                    .search("Android Example", 1, false, false, onlySources = setOf("C411"))
                check(page.results.single().title == "Android Example {edition}")
                check(!page.partial)
                stage = "download recovery and offline file integrity"
                AudioDownloadQualification.run(targetContext)
            }
            stage = "original release name wrapping"
            ReleaseNameLayoutQualification.run(this)
            result.putString("stream", "Android runtime checks passed: title cleanup, resolver preservation, XML search, metadata enrichment, expired download links, interrupted multi-file recovery, offline file integrity and original release name wrapping.\n")
            sendStatus(0, status)
            finish(-1, result)
        } catch (failure: Exception) {
            // Class names suffice for diagnostics; messages can contain request or resolver data.
            result.putString("stream", "Android runtime checks failed: $stage (${failure.javaClass.simpleName})\n")
            status.putString("stack", "$stage (${failure.javaClass.simpleName})")
            sendStatus(-2, status)
            finish(-1, result)
        }
    }
}
