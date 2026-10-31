package io.github.lonode.debridbooks.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.audio.AudioDownloadService
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map

@Composable fun BookPersonalActions(account: String, item: SearchItem) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val key = remember(item.id) { MobileContract.localId(item.id) }
    val all by remember(app) { app.database.audio().observePersonal() }.collectAsState(initial = emptyList())
    val downloads by remember(app) { app.database.audio().observeDownloads() }.collectAsState(initial = emptyList())
    val extras = remember(all, account, key) { all.firstOrNull { it.accountId == account && it.itemId == key }.extras() }
    val download = downloads.firstOrNull { it.accountId == account && it.itemId == key }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    fun change(action: suspend () -> Unit) { scope.launch {
        try { app.audio.remember(account, item); action(); message = "" }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = "Could not save this change. Please retry." }
    } }
    OutlinedButton(onClick = { change { app.database.audio().updatePersonal(account, key) { it.copy(saved = !it.saved) } } }) {
        Text(if (extras.saved) "Saved · Want to Listen" else "Want to Listen")
    }
    Text("Your rating", style = MaterialTheme.typography.labelMedium)
    Row {
        (1..5).forEach { stars -> TextButton(modifier = Modifier.weight(1f).semantics { contentDescription = "Rate $stars of 5 stars" },
            onClick = { change { app.database.audio().updatePersonal(account, key) { it.copy(rating = if (it.rating == stars) 0 else stars) } } }) {
            Text(if (stars <= extras.rating) "★" else "☆")
        } }
    }
    if (download?.state != "COMPLETE") Button(enabled = download?.state !in setOf("QUEUED", "RUNNING"), onClick = {
        change {
            app.audio.queue(account, item)
            app.startForegroundService(Intent(app, AudioDownloadService::class.java))
        }
    }) { Text(if (download?.state == "WAITING") "Check and download when ready" else "Download audiobook") }
    if (download?.state == "PAUSED" && download.status?.startsWith("Space") == true) TextButton(onClick = {
        change { app.audio.queue(account, item, allowLowSpace = true); app.startForegroundService(Intent(app, AudioDownloadService::class.java)) }
    }) { Text("Download anyway · limited free space") }
    download?.let { entry ->
        Text("Download: ${entry.state.lowercase()}" + if (entry.bytes > 0) " · ${entry.bytes / 1_000_000} MB" else "", style = MaterialTheme.typography.bodySmall)
        entry.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
}
