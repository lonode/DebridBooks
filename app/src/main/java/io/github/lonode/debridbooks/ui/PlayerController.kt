package io.github.lonode.debridbooks.ui

import android.content.ComponentName
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.*
import io.github.lonode.debridbooks.playback.PlaybackService

@Composable fun rememberPlayerController(): MediaController? {
    val context = LocalContext.current.applicationContext
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({ try { controller = future.get() } catch (_: Exception) { controller = null } },
            java.util.concurrent.Executor { android.os.Handler(android.os.Looper.getMainLooper()).post(it) })
        onDispose { MediaController.releaseFuture(future) }
    }
    return controller
}
