package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.R
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import io.github.lonode.debridbooks.network.standalone.SetupProblem

/** ViewModel-owned draft survives rotation without copying secrets into Android saved state. */
class SetupDraft {
    var apiKey by mutableStateOf("")
    var c411Enabled by mutableStateOf(false); var c411Key by mutableStateOf(""); var c411Url by mutableStateOf("https://c411.org/api/torznab")
    var tr4kerEnabled by mutableStateOf(false); var tr4kerKey by mutableStateOf(""); var tr4kerUrl by mutableStateOf("https://tr4ker.net/api/torznab/all")
    var yggEnabled by mutableStateOf(false); var yggKey by mutableStateOf(""); var yggUrl by mutableStateOf("https://api.yggreborn.org/api")
    var v3xEnabled by mutableStateOf(false); var v3xKey by mutableStateOf(""); var v3xUrl by mutableStateOf("https://api.v3x.club/torznab/api")
    var knabenEnabled by mutableStateOf(true)
    var bayEnabled by mutableStateOf(false); var x1337Enabled by mutableStateOf(false)
    var abbEnabled by mutableStateOf(true)
    var jackettEnabled by mutableStateOf(false); var jackettUrl by mutableStateOf(""); var jackettKey by mutableStateOf("")
    var language by mutableStateOf(""); var format by mutableStateOf(""); var maxSize by mutableStateOf("")
    fun load(config: StandaloneConfig) {
        knabenEnabled = config.knabenEnabled
        bayEnabled = config.bayEnabled; x1337Enabled = config.x1337Enabled
        v3xEnabled = config.v3xApiKey.isNotBlank(); v3xKey = config.v3xApiKey; v3xUrl = config.v3xUrl
        apiKey = config.apiKey
        c411Enabled = config.c411ApiKey.isNotBlank(); c411Key = config.c411ApiKey; c411Url = config.c411Url
        tr4kerEnabled = config.tr4kerApiKey.isNotBlank(); tr4kerKey = config.tr4kerApiKey; tr4kerUrl = config.tr4kerUrl
        yggEnabled = config.yggrebornPasskey.isNotBlank(); yggKey = config.yggrebornPasskey; yggUrl = config.yggrebornUrl
        abbEnabled = config.abbDomain.isNotBlank()
        jackettEnabled = config.jackettUrl.isNotBlank(); jackettUrl = config.jackettUrl; jackettKey = config.jackettApiKey
        language = config.languageFilter; format = config.preferredFormat; maxSize = config.maxSizeGB?.toString().orEmpty()
    }
    fun config(): StandaloneConfig {
        if (c411Enabled && c411Key.isBlank()) throw SetupProblem("Enter your C411 API key.")
        if (tr4kerEnabled && tr4kerKey.isBlank()) throw SetupProblem("Enter your Tr4ker API key.")
        if (v3xEnabled && v3xKey.isBlank()) throw SetupProblem("Enter your V3X API key.")
        if (yggEnabled && yggKey.isBlank()) throw SetupProblem("Enter your YggReborn passkey.")
        if (jackettEnabled && (jackettUrl.isBlank() || jackettKey.isBlank())) throw SetupProblem("Enter your Jackett URL and API key.")
        val size = maxSize.trim().takeIf { it.isNotEmpty() }?.let { it.replace(',', '.').toDoubleOrNull() ?: throw SetupProblem("Enter a positive maximum size.") }
        return StandaloneConfig(apiKey.trim(), if (c411Enabled) c411Key.trim() else "", c411Url.trim(),
            if (tr4kerEnabled) tr4kerKey.trim() else "", tr4kerUrl.trim(), if (yggEnabled) yggKey.trim() else "", yggUrl.trim(),
            if (abbEnabled) StandaloneConfig.DEFAULT_ABB_DOMAIN else "", if (jackettEnabled) jackettUrl.trim().trimEnd('/') else "",
            if (jackettEnabled) jackettKey.trim() else "", language, format, size, bayEnabled = bayEnabled, x1337Enabled = x1337Enabled, knabenEnabled = knabenEnabled,
            v3xApiKey = if (v3xEnabled) v3xKey.trim() else "", v3xUrl = v3xUrl.trim()).validate()
    }
}

@Composable
fun StandaloneSetup(draft: SetupDraft, busy: Boolean, connected: Boolean, onLanguageChange: (String) -> Unit, onSave: () -> Unit) {
    Text(stringResource(R.string.standalone_setup), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.standalone_setup_help))
    SecretField(draft.apiKey, { draft.apiKey = it }, "AllDebrid API key", busy)
    Text(stringResource(R.string.search_sources), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.source_keys_help))
    SearchSourceGroup(stringResource(R.string.french_trackers),
        stringResource(R.string.french_tracker_names),
        listOf(draft.c411Enabled, draft.tr4kerEnabled, draft.yggEnabled, draft.v3xEnabled).count { it }, 4) {
        SourceToggle("C411", draft.c411Enabled, busy) { draft.c411Enabled = it }
        if (draft.c411Enabled) SecretField(draft.c411Key, { draft.c411Key = it }, "C411 API key", busy)
        SourceToggle("Tr4ker", draft.tr4kerEnabled, busy) { draft.tr4kerEnabled = it }
        if (draft.tr4kerEnabled) SecretField(draft.tr4kerKey, { draft.tr4kerKey = it }, "Tr4ker API key", busy)
        SourceToggle("V3X", draft.v3xEnabled, busy) { draft.v3xEnabled = it }
        if (draft.v3xEnabled) SecretField(draft.v3xKey, { draft.v3xKey = it }, "V3X API key", busy)
        SourceToggle("YggReborn", draft.yggEnabled, busy) { draft.yggEnabled = it }
        if (draft.yggEnabled) SecretField(draft.yggKey, { draft.yggKey = it }, "YggReborn API passkey", busy)
    }
    SourceToggle("Knaben (multi-tracker search)", draft.knabenEnabled, busy) { draft.knabenEnabled = it }
    Text("Public sources need no API key and are skipped for French-only searches. Sites may block access; each source reports its own status.", style = MaterialTheme.typography.bodySmall)
    SourceToggle("AudiobookBay", draft.abbEnabled, busy) { draft.abbEnabled = it }
    var advanced by remember { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) {
        Text(stringResource(if (advanced) R.string.hide_advanced_sources else R.string.advanced_sources))
    }
    if (advanced) {
        if (draft.c411Enabled) EndpointField(draft.c411Url, { draft.c411Url = it }, "C411 endpoint", busy)
        if (draft.tr4kerEnabled) EndpointField(draft.tr4kerUrl, { draft.tr4kerUrl = it }, "Tr4ker endpoint", busy)
        if (draft.v3xEnabled) EndpointField(draft.v3xUrl, { draft.v3xUrl = it }, "V3X endpoint", busy)
        if (draft.yggEnabled) EndpointField(draft.yggUrl, { draft.yggUrl = it }, "YggReborn endpoint", busy)
        SourceToggle("Jackett (optional)", draft.jackettEnabled, busy) { draft.jackettEnabled = it }
        if (draft.jackettEnabled) {
            EndpointField(draft.jackettUrl, { draft.jackettUrl = it }, "Jackett HTTPS URL", busy)
            SecretField(draft.jackettKey, { draft.jackettKey = it }, "Jackett API key", busy)
            Text(stringResource(R.string.jackett_optional_help))
        }
    }
    Text(stringResource(R.string.mangadex_no_key))
    Text(stringResource(R.string.book_language))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "All", "fr" to "French", "en" to "English").forEach { (value, label) ->
            FilterChip(draft.language == value, { onLanguageChange(value) }, enabled = !busy, label = { Text(label) })
        }
    }
    Text(stringResource(R.string.language_filter_help), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.preferred_format))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "Any", "mp3" to "MP3", "m4b" to "M4B").forEach { (value, label) ->
            FilterChip(draft.format == value, { draft.format = value }, enabled = !busy, label = { Text(label) })
        }
    }
    OutlinedTextField(draft.maxSize, { draft.maxSize = it }, label = { Text(stringResource(R.string.maximum_size)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (connected) R.string.test_save_keys else R.string.test_start))
    }
}
@Composable
private fun SearchSourceGroup(title: String, names: String, enabledCount: Int, total: Int, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) R.string.source_group_expanded else R.string.source_group_collapsed)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansion }) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(names, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.source_group_enabled, enabledCount, total),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                    modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f))
            }
        }
        if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable
private fun SecretField(value: String, change: (String) -> Unit, label: String, busy: Boolean) {
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(value, change, label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = !busy) {
                Icon(painterResource(if (visible) R.drawable.ic_key_hidden else R.drawable.ic_key_visible),
                    contentDescription = stringResource(if (visible) R.string.hide_key else R.string.show_key, label))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
@Composable
private fun EndpointField(value: String, change: (String) -> Unit, label: String, busy: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
@Composable
private fun SourceToggle(label: String, checked: Boolean, busy: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Checkbox(checked, change, enabled = !busy, modifier = Modifier.semantics { contentDescription = label })
    }
}
