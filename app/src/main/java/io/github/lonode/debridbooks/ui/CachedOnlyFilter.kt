package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.data.ListeningPreferences
import kotlinx.coroutines.*

/** One persisted local results filter, shared by audio and torrent-comic search. */
@Composable
internal fun CachedOnlyFilter(enforced: Boolean, allDebrid: Boolean) {
    val app=LocalContext.current.applicationContext as DebridApplication
    val prefs by app.preferences.preferences.collectAsState(initial=ListeningPreferences())
    val scope=rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val checked=prefs.options.instantOnly || enforced
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(value=checked,enabled=!saving && !enforced,role=Role.Switch,onValueChange={ enabled ->
            saving=true;error=false
            scope.launch {
                try { app.preferences.setOptions { it.copy(instantOnly=enabled) } }
                catch(cancel: CancellationException){throw cancel}
                catch(_: Exception){error=true}
                finally {saving=false}
            }
        }),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(allDebrid) "Ready in my account" else "Ready only",style=MaterialTheme.typography.titleSmall)
                Text(if(enforced) "Required by the account-ready setting in Settings."
                    else if(allDebrid) "Only confirmed ready in your AllDebrid account. Other uploads may still play instantly."
                    else "Only uploads marked ready by your provider.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=checked,onCheckedChange=null,enabled=!saving && !enforced)
        }
        if(error) Text("Could not save this filter. Please retry.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }
}
