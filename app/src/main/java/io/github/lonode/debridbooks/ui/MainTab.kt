package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class MainTab(val label: String) { Library("Library"), Search("Search"), Player("Player"), Settings("Settings") }
@Composable
fun NavigationIcon(tab: MainTab) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        when (tab) {
            MainTab.Library -> {
                for (x in listOf(4f, 10f, 16f)) drawRect(color, Offset(x*u, 4*u), Size(4*u, 16*u), style = Stroke(1.7f*u))
            }
            MainTab.Search -> { drawCircle(color, 7*u, Offset(10*u, 10*u), style = Stroke(2*u)); drawLine(color, Offset(15*u, 15*u), Offset(21*u, 21*u), 2*u) }
            MainTab.Player -> drawPath(Path().apply { moveTo(7*u, 4*u); lineTo(20*u, 12*u); lineTo(7*u, 20*u); close() }, color)
            MainTab.Settings -> {
                for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                    drawLine(color, Offset(3*u, y*u), Offset(21*u, y*u), 1.5f*u)
                    drawCircle(color, 2.5f*u, Offset(x*u, y*u))
                }
            }
        }
    }
}
