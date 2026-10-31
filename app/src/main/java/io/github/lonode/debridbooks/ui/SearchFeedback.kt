package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.safeSource

@Composable
fun SearchFeedback(session: SearchSession, busy: Boolean, onRetry: (SearchRequest) -> Unit, visibleCount: Int = session.results.size) {
    var confirmForce by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), session.sources) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    if (confirmForce) AlertDialog(onDismissRequest = { confirmForce = false },
        title = { Text("Force an AudiobookBay retry?") },
        text = { Text("The cooldown reduces requests after AudiobookBay blocks or stops responding. Forcing repeated requests can trigger or extend an IP ban. Waiting is safer.\n\nThis allows one paced AudiobookBay attempt. Other sources will not be searched again.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            confirmForce = false
            val request = session.attempt ?: SearchRequest(session.query)
            onRetry(request.copy(refresh = false, sources = setOf("AudiobookBay"), forceAbb = true))
        }) { Text("Force retry") } },
        dismissButton = { TextButton(onClick = { confirmForce = false }) { Text("Keep waiting") } })
    if (session.sources.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.pages.isNotEmpty()) Text(searchCountLabel(session.results.size, visibleCount), style = MaterialTheme.typography.labelMedium)
            session.sources.forEach { source ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source.state == SearchSourceState.Pending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    val loaded = session.loadedFrom(source.source)
                    val detail = when (source.state) {
                        SearchSourceState.Pending -> if (source.source == "AllDebrid") "Checking account readiness…" else if (source.source == "Book details") "Adding covers… ${source.count} found" else "Searching…"
                        SearchSourceState.Complete -> if (source.source == "AllDebrid") "Account readiness checked" else if (source.source == "Book details") "${source.count} covers found" else "$loaded loaded"
                        SearchSourceState.Partial -> "$loaded loaded · ${searchIssueLabel(source.issue, source.source)}"
                        SearchSourceState.Failed -> searchIssueLabel(source.issue, source.source)
                        SearchSourceState.Cancelled -> "Cancelled"
                    }
                    val label = if (loaded > 0 && source.state in setOf(SearchSourceState.Pending, SearchSourceState.Failed, SearchSourceState.Cancelled))
                        "$loaded loaded · $detail" else detail
                    Column(Modifier.weight(1f)) {
                        Text("${safeSource(source.source)} · $label${if(source.cached) " · Cached results" else ""}", style = MaterialTheme.typography.bodySmall)
                        source.cooldownUntil?.takeIf { it > now }?.let { until ->
                            val minutes = ((until - now + 59999) / 60000).coerceAtLeast(1)
                            Text("Paused · retry in ${minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (source.source == "AudiobookBay" && (source.cooldownUntil ?: 0) > now)
                        TextButton(enabled = !busy, onClick = { confirmForce = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Force", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (session.sources.any { it.source == "AllDebrid" && it.state == SearchSourceState.Pending })
                Text("Ready-in-account results appear after the account check. Global instant availability is unknown.", style = MaterialTheme.typography.bodySmall)
        }
    }
    val feedback = session.feedbackMessage
    if (feedback != null || session.warnings.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            session.warnings.filter { warning -> session.sources.none { it.source == warning.source && it.issue == warning.code } }.take(32).forEach { Text(it.displayWarning(), style = MaterialTheme.typography.bodySmall) }
            session.retry?.let { request -> TextButton(enabled = !busy, onClick = { onRetry(request) }) { Text("Retry search") } }
        }
    }
}
