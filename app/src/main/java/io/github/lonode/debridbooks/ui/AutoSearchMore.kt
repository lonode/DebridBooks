package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.domain.SearchRequest
import io.github.lonode.debridbooks.domain.SearchSession
import io.github.lonode.debridbooks.domain.searchCountLabel

/** One request per bottom-of-list encounter; a filtered/short page cannot cause an unbounded fetch loop. */
@Composable
internal fun AutoSearchMore(session: SearchSession, busy: Boolean, onRequest: (SearchRequest) -> Unit, visibleCount: Int = session.results.size) {
    if (session.pages.isEmpty()) return
    var visible by remember(session.query) { mutableStateOf(false) }
    var consumed by remember(session.query) { mutableStateOf(false) }
    val next = session.automaticNextRequest()
    LaunchedEffect(visible, busy, next) {
        if (!visible) consumed = false
        else if (!busy && !consumed && next != null) {
            consumed = true
            onRequest(next)
        }
    }
    Column(Modifier.fillMaxWidth().onGloballyPositioned { visible = it.boundsInWindow().height > 0f }
        .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(searchCountLabel(session.results.size, visibleCount), style = MaterialTheme.typography.bodySmall)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if ((session.attempt?.number ?: 1) > 1) "Loading more results…" else "Loading search results…", style = MaterialTheme.typography.bodySmall)
        } else if (session.wasCancelled || session.failure != null) {
            Text(if (session.wasCancelled) "Search cancelled. Loaded results are kept." else "Could not load more results.", style = MaterialTheme.typography.bodySmall)
            session.retry?.let { request -> TextButton(onClick = { onRequest(request) }) { Text("Retry search") } }
        } else Text(if (next == null) "End of available results" else "Scroll to load more results", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
