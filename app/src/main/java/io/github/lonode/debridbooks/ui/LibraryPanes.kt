package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Lazily retain the two library panes, measuring and exposing only the selected pane. */
@Composable
fun LibraryPanes(selected: Int, audio: @Composable () -> Unit, comics: @Composable () -> Unit) {
    var visited by remember { mutableIntStateOf(1 shl selected) }
    SideEffect { visited = visited or (1 shl selected) }
    Layout(content = {
        Column(if (selected == 0) Modifier else Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (selected == 0 || (visited and 1) != 0) audio()
        }
        Column(if (selected == 1) Modifier else Modifier.clearAndSetSemantics {}) {
            if (selected == 1 || (visited and 2) != 0) comics()
        }
    }) { panes, constraints ->
        val active = panes[selected].measure(constraints)
        layout(active.width, active.height) { active.placeRelative(0, 0) }
    }
}
