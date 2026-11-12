package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.domain.InstallLink
import io.github.lonode.debridbooks.domain.MobileContract
import io.github.lonode.debridbooks.network.Connection
import java.net.URI
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class SetupProblem(message: String) : IllegalArgumentException(message)
internal inline fun setupRequire(value: Boolean, message: () -> Any = { "Invalid setup." }) { if (!value) throw SetupProblem(message().toString()) }

/** Secrets live only in the encrypted connection record, never saved-state bundles. */
@Serializable
class StandaloneConfig(
    val apiKey: String,
    val c411ApiKey: String = "", val c411Url: String = "https://c411.org/api/torznab",
    val tr4kerApiKey: String = "", val tr4kerUrl: String = "https://tr4ker.net/api/torznab/all",
    val yggrebornPasskey: String = "", val yggrebornUrl: String = "https://api.yggreborn.org/api",
    val abbDomain: String = DEFAULT_ABB_DOMAIN, val jackettUrl: String = "", val jackettApiKey: String = "",
    val languageFilter: String = "", val preferredFormat: String = "", val maxSizeGB: Double? = null,
    // Retained for saved-config compatibility; account-only restriction is currently disabled.
    val instantOnly: Boolean = false,
    // Legacy Bay/1337x flags remain readable; these sources now run automatically outside French-only searches.
    val bayEnabled: Boolean = false, val x1337Enabled: Boolean = false, val knabenEnabled: Boolean = true,
    val v3xApiKey: String = "", val v3xUrl: String = "https://api.v3x.club/torznab/api",
) {
    val publicSourcesAllowed: Boolean get() = languageFilter != "fr"
    fun withLanguageFilter(language: String): StandaloneConfig {
        setupRequire(language in setOf("", "fr", "en")) { "Invalid search language." }
        return StandaloneConfig(apiKey = apiKey, c411ApiKey = c411ApiKey, c411Url = c411Url,
            tr4kerApiKey = tr4kerApiKey, tr4kerUrl = tr4kerUrl, yggrebornPasskey = yggrebornPasskey,
            yggrebornUrl = yggrebornUrl, abbDomain = abbDomain, jackettUrl = jackettUrl,
            jackettApiKey = jackettApiKey, languageFilter = language, preferredFormat = preferredFormat,
            maxSizeGB = maxSizeGB, instantOnly = instantOnly, bayEnabled = bayEnabled, x1337Enabled = x1337Enabled, knabenEnabled = knabenEnabled, v3xApiKey = v3xApiKey, v3xUrl = v3xUrl)
    }
    fun validate(): StandaloneConfig = apply {
        setupRequire(apiKey.isNotBlank() && apiKey.length <= 1024 && !apiKey.any { it.isWhitespace() || it.isISOControl() }) { "Enter a valid AllDebrid API key." }
        setupRequire(languageFilter in setOf("", "fr", "en") && preferredFormat in setOf("", "mp3", "m4b")) { "Invalid search preferences." }
        setupRequire(maxSizeGB == null || maxSizeGB.isFinite() && maxSizeGB > 0 && maxSizeGB <= 100000) { "Enter a positive maximum size." }
        trackers().forEach { source ->
            setupRequire(source.key.length <= 2048 && !source.key.any { it.isWhitespace() || it.isISOControl() }) { "Invalid tracker key." }
            endpoint(source.url)
        }
        setupRequire(jackettUrl.isBlank() == jackettApiKey.isBlank()) { "Enter both the Jackett URL and API key." }
        if (jackettUrl.isNotBlank()) endpoint(jackettUrl)
        if (abbDomain.isNotBlank()) {
            val uri = try { URI("https://$abbDomain") } catch (_: Exception) { throw SetupProblem("Enter a valid AudiobookBay domain.") }
            setupRequire(uri.host != null && uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "Enter an AudiobookBay domain without a path." }
        }
    }
    fun trackers(): List<Tracker> = listOf(
        Tracker("C411", c411Url, c411ApiKey, "3030", "7030", 4100),
        Tracker("Tr4ker", tr4kerUrl, tr4kerApiKey, "3030", "7030", 1000),
        Tracker("V3X", v3xUrl, v3xApiKey, "3030", "7030", 2000),
        Tracker("YggReborn", yggrebornUrl, yggrebornPasskey, "7105", "7102,7103,7104", 2100),
    ).filter { it.key.isNotBlank() }
    companion object {
        const val DEFAULT_ABB_DOMAIN = "audiobookbay.lu"
        fun endpoint(value: String) {
            try {
                val uri = URI(value)
                setupRequire(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            } catch (_: Exception) { throw SetupProblem("Service endpoints must be HTTPS URLs without credentials, queries or fragments.") }
        }
        /** Decode locally: no request to the old server, and keep the old library's account identity. */
        fun migrate(connection: Connection): Connection {
            if (connection.standalone != null) { connection.standalone.validate(); return connection }
            try {
                val prefix = InstallLink.parse(connection.prefix).prefix
                val config = Json.parseToJsonElement(Base64.getUrlDecoder().decode(URI(prefix).rawPath.drop(1)).toString(Charsets.UTF_8)).jsonObject
                fun str(key: String, fallback: String = "") = config[key]?.jsonPrimitive?.contentOrNull ?: fallback
                setupRequire(str("provider") == "alldebrid")
                val settings = StandaloneConfig(str("apiKey"), str("c411ApiKey"), str("c411Url", "https://c411.org/api/torznab"),
                    str("tr4kerApiKey"), str("tr4kerUrl", "https://tr4ker.net/api/torznab/all"),
                    str("yggrebornPasskey"), str("yggrebornUrl", "https://api.yggreborn.org/api"),
                    str("abbDomain", DEFAULT_ABB_DOMAIN).removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    str("jackettUrl"), str("jackettApiKey"), str("languageFilter"), str("preferredFormat"),
                    config["maxSizeGB"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }, config["instantOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    v3xApiKey = str("v3xApiKey"), v3xUrl = str("v3xUrl", "https://api.v3x.club/torznab/api")).validate()
                return Connection(accountId = connection.accountId, standalone = settings)
            } catch (_: Exception) { throw IllegalArgumentException("Set up your AllDebrid and source keys to continue. Your offline library is still available.") }
        }
        fun connect(config: StandaloneConfig, previous: Connection? = null): Connection {
            config.validate()
            val account = if (previous?.standalone?.apiKey == config.apiKey) previous.accountId else MobileContract.localId("alldebrid:${config.apiKey}")
            return Connection(accountId = account, standalone = config)
        }
    }
}
class Tracker(val name: String, val url: String, val key: String, val audioCategory: String, val comicCategory: String, val delayMs: Long)
