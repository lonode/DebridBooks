package io.github.lonode.debridbooks.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.data.BackupPreview
import io.github.lonode.debridbooks.playback.PlaybackAction
import kotlinx.coroutines.*

@Composable fun BackupSettings() {
    val app = LocalContext.current.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val encrypted = bytes; bytes = null
        if (uri != null && encrypted != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(encrypted) } }
                message = "Encrypted backup saved. Keep your passphrase somewhere safe."
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "Backup could not be saved. Try another document location." }
            finally { busy = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                            check(output.size() + n <= 15 * 1024 * 1024); output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                }
                mode = "restore"; password = ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "This backup could not be read or is too large." }
            finally { busy = false }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup and restore", style = MaterialTheme.typography.titleLarge)
            Text("Protect your library, comic history, bookmarks, statistics and preferences with a passphrase. Downloaded files and the server install link are excluded.")
            Row {
                TextButton(enabled = !busy, onClick = { mode = "export"; password = ""; message = "" }) { Text("Save backup") }
                TextButton(enabled = !busy, onClick = { open.launch(arrayOf("application/octet-stream", "application/json", "text/plain", "*/*")) }) { Text("Restore backup") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
    }
    if (mode.isNotEmpty()) AlertDialog(onDismissRequest = { if (!busy) { mode = ""; password = ""; bytes = null } },
        title = { Text(if (mode == "export") "Protect your backup" else "Unlock backup") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use at least 10 characters. You need this passphrase to restore; the app cannot recover it. Resolver IDs are protected because they may contain tracker credentials.")
            OutlinedTextField(password, { password = it.take(1024) }, label = { Text("Backup passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (message.isNotEmpty()) Text(message)
        } }, confirmButton = { TextButton(enabled = !busy && password.length >= 10, onClick = { scope.launch {
            busy = true; val secret = password.toCharArray()
            try {
                if (mode == "export") {
                    bytes = app.backup.export(secret); mode = ""; password = ""; save.launch("DebridBooks-backup.debridbooks")
                } else {
                    preview = app.backup.preview(checkNotNull(bytes), secret); bytes = null; mode = ""; password = ""
                }
                message = ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "Could not unlock or validate this backup. Check the passphrase and file." }
            finally { secret.fill('\u0000'); busy = false }
        } }) { Text(if (mode == "export") "Save" else "Preview") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { mode = ""; password = ""; bytes = null }) { Text("Cancel") } })
    preview?.let { selected -> AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Merge this backup?") },
        text = { Text("${selected.books} audiobooks, ${selected.comics} comic/manga histories, ${selected.bookmarks} bookmarks.\n\nKeeps newer reading/listening positions and merges statistics without double counting. Playback will stop and downloads pause. Preferences, including network settings, will be restored. Reconnect the same server configuration to stream restored books.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try {
                app.playbackRuntime.send(PlaybackAction.Close)
                app.stopService(Intent(app, io.github.lonode.debridbooks.audio.AudioDownloadService::class.java))
                app.stopService(Intent(app, io.github.lonode.debridbooks.comics.ComicDownloadService::class.java))
                val settings = app.backup.merge(selected); app.audioLibraryItems.clear()
                preview = null
                message = if (settings) "Library merged. Existing downloaded files were preserved." else "Library merged; some preferences could not be restored. You can retry the same backup safely."
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "The library could not be merged. Please retry." }
            finally { busy = false }
        } }) { Text("Merge library") } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } }) }
}
