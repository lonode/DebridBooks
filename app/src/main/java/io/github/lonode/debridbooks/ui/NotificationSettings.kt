package io.github.lonode.debridbooks.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.data.*
import kotlinx.coroutines.*

@Composable fun NotificationSettings(options: AppOptions) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf("") }
    var denied by remember { mutableStateOf(false) }
    fun set(kind: String, value: Boolean) { scope.launch { app.preferences.setOptions { if (kind == "ready") it.copy(notifyReady = value) else it.copy(notifyReleases = value) } } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = pending; pending = ""; denied = !granted
        if (kind.isNotEmpty() && granted) set(kind, true)
    }
    Text("Notifications", style = MaterialTheme.typography.titleMedium)
    listOf("ready" to "Books ready to download", "series" to "New books in your series").forEach { (kind, label) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(if (kind == "ready") options.notifyReady else options.notifyReleases, onCheckedChange = { value ->
                if (value && android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pending = kind; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else set(kind, value)
            }, modifier = Modifier.semantics { contentDescription = label })
        }
    }
    Text("Background checks are scheduled by Android; timing is approximate. Series alerts use public audiobook catalog data.", style = MaterialTheme.typography.bodySmall)
    if (denied) TextButton(onClick = { app.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Enable notifications in Android settings") }
}
