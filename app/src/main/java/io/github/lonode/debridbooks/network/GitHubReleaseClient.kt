package io.github.lonode.debridbooks.network

import io.github.lonode.debridbooks.network.standalone.DirectHttp
import io.github.lonode.debridbooks.network.standalone.DirectRequest
import io.github.lonode.debridbooks.network.standalone.DirectTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Only public release metadata is requested; provider configuration is never involved. */
data class ReleaseUpdate(val version: String, val releaseUrl: String)

internal class GitHubReleaseClient(private val http: DirectTransport = DirectHttp) {
    suspend fun newerThan(installed: String): ReleaseUpdate? = withTimeoutOrNull(8000) {
        try {
            val response = http.execute(DirectRequest(API_URL, mapOf(
                "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"), limit = 256_000))
            if (response.status == 200) parse(response.text(), installed) else null
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null } // Offline, rate-limited and malformed responses never interrupt launch.
    }

    companion object {
        const val REPOSITORY = "https://github.com/lonode/DebridBooks"
        const val API_URL = "https://api.github.com/repos/lonode/DebridBooks/releases/latest"
        private val stableTag = Regex("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
        private fun version(tag: String): List<Int>? = stableTag.matchEntire(tag)?.groupValues?.drop(1)?.map { it.toInt() }
        internal fun parse(body: String, installed: String): ReleaseUpdate? {
            val current = version(installed.removeSuffix("-debug")) ?: return null
            val release = Json.parseToJsonElement(body) as? JsonObject ?: return null
            if ((release["draft"] as? JsonPrimitive)?.booleanOrNull != false ||
                (release["prerelease"] as? JsonPrimitive)?.booleanOrNull != false) return null
            val tag = (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val latest = version(tag) ?: return null
            if (latest.zip(current).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } != 1) return null
            val releaseUrl = "$REPOSITORY/releases/tag/$tag"
            if ((release["html_url"] as? JsonPrimitive)?.contentOrNull != releaseUrl) return null
            val name = "DebridBooks-${tag.removePrefix("v")}.apk"
            val apkUrl = "$REPOSITORY/releases/download/$tag/$name"
            val assets = release["assets"] as? JsonArray ?: return null
            val ready = assets.filterIsInstance<JsonObject>().any {
                (it["name"] as? JsonPrimitive)?.contentOrNull == name &&
                    (it["state"] as? JsonPrimitive)?.contentOrNull == "uploaded" &&
                    ((it["size"] as? JsonPrimitive)?.longOrNull ?: 0) > 0 &&
                    (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull == apkUrl
            }
            return if (ready) ReleaseUpdate(tag.removePrefix("v"), releaseUrl) else null
        }
    }
}
