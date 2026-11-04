package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.ui.ReleaseName
import java.io.File

/** Verify the real Compose component at a narrow width without any provider or account fixture. */
internal object ReleaseNameLayoutQualification {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val raw = "Original.Dotted_Release." + "Keep.Dots_And.Tags.".repeat(16) + "[EN].m4b"
        val activity = instrumentation.startActivitySync(
            Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ComponentActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 64.dp)) {
                                Box(Modifier.width(160.dp)) { ReleaseName(raw) }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5000
            var nameNode: AccessibilityNodeInfo? = null
            while (nameNode == null && SystemClock.uptimeMillis() < deadline) {
                nameNode = find(instrumentation.uiAutomation.rootInActiveWindow, raw)
                if (nameNode == null) Thread.sleep(100)
            }
            val node = checkNotNull(nameNode) { "Original name is missing or modified" }
            val bounds = Rect().also(node::getBoundsInScreen)
            val density = context.resources.displayMetrics.density
            check(bounds.width() <= 160 * density + 2) { "Original name exceeds the available width" }
            check(bounds.height() > 80 * density) { "Long original name did not wrap into multiple lines" }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(context.cacheDir, "release-wrap.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun find(root: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == value) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }
}
