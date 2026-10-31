package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.data.*
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AdvancedSettings(options: AppOptions) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    fun save(change: (AppOptions) -> AppOptions) { scope.launch {
        try { app.preferences.setOptions(change); error = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = true }
    } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playback and appearance", style = MaterialTheme.typography.titleLarge)
            @Composable fun toggle(label: String, checked: Boolean, change: (AppOptions, Boolean) -> AppOptions) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(checked, onCheckedChange = { value -> save { change(it, value) } }, modifier = Modifier.semantics { contentDescription = label })
                }
            }
            toggle("Remove offline audio when a book finishes", options.autoDeleteFinished) { old, value -> old.copy(autoDeleteFinished = value) }
            if (options.autoDeleteFinished) Text("Completed audio files are removed after finishing. The book, bookmarks and saved place remain.", style = MaterialTheme.typography.bodySmall)
            toggle("Smart rewind after a pause", options.smartRewind) { old, value -> old.copy(smartRewind = value) }
            toggle("Restore last book paused on launch", options.autoResume) { old, value -> old.copy(autoResume = value) }
            toggle("Fade out at the end of a sleep timer", options.sleepFade) { old, value -> old.copy(sleepFade = value) }
            toggle("Automatic bedtime timer", options.bedtimeTimer) { old, value -> old.copy(bedtimeTimer = value) }
            if (options.bedtimeTimer) {
                Text("Starts when playback begins between the chosen hour and 06:00.", style = MaterialTheme.typography.bodySmall)
                FlowRow { listOf(20,21,22,23,0).forEach { hour -> FilterChip(options.bedtimeStart == hour,
                    onClick = { save { it.copy(bedtimeStart = hour) } }, label = { Text("%02d:00".format(hour)) }) } }
                FlowRow { listOf(15,30,45,60).forEach { minutes -> FilterChip(options.bedtimeMinutes == minutes,
                    onClick = { save { it.copy(bedtimeMinutes = minutes) } }, label = { Text("$minutes min") }) } }
            }
            toggle("Show remaining time in the player", options.remainingTime) { old, value -> old.copy(remainingTime = value) }
            toggle("Group matching search uploads", options.groupUploads) { old, value -> old.copy(groupUploads = value) }
            NotificationSettings(options)
            Text("Accent colour", style = MaterialTheme.typography.titleMedium)
            FlowRow { listOf("violet","blue","teal","orange","pink").forEach { name -> FilterChip(options.accent == name,
                onClick = { save { it.copy(accent = name) } }, label = { Text(name.replaceFirstChar { it.uppercase() }) }) } }
            if (error) Text("Could not save preferences. Please retry.", color = MaterialTheme.colorScheme.error)
        }
    }
}
