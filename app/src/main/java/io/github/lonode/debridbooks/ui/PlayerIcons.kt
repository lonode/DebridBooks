package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class PlayerSymbol { Queue, Bookmark, Sleep, Time, Car, Previous, Next, Info, Undo, Range }

/** Project-owned vector-style icons; the enclosing button supplies its accessible label. */
@Composable
internal fun PlayerIcon(symbol: PlayerSymbol, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u, y*u), Offset(x2*u, y2*u), stroke.width, StrokeCap.Round)
        when (symbol) {
            PlayerSymbol.Queue -> {
                line(3f, 6f, 21f, 6f); line(3f, 12f, 12f, 12f); line(3f, 18f, 12f, 18f)
                drawPath(Path().apply { moveTo(16*u, 10*u); lineTo(22*u, 15*u); lineTo(16*u, 20*u); close() }, color)
            }
            PlayerSymbol.Bookmark -> drawPath(Path().apply {
                moveTo(6*u, 3*u); lineTo(18*u, 3*u); lineTo(18*u, 21*u); lineTo(12*u, 17*u); lineTo(6*u, 21*u); close()
            }, color, style = stroke)
            PlayerSymbol.Sleep -> {
                drawPath(Path().apply {
                    moveTo(19*u, 16*u); cubicTo(12*u, 18*u, 6*u, 12*u, 9*u, 4*u)
                    cubicTo(3*u, 6*u, 2*u, 14*u, 7*u, 18*u); cubicTo(11*u, 22*u, 17*u, 21*u, 19*u, 16*u); close()
                }, color, style = stroke)
                line(17f, 3f, 17f, 7f); line(15f, 5f, 19f, 5f)
            }
            PlayerSymbol.Time -> {
                drawCircle(color, 9*u, Offset(12*u, 12*u), style = stroke)
                line(12f, 6f, 12f, 12f); line(12f, 12f, 16f, 14f)
            }
            PlayerSymbol.Car -> {
                drawRoundRect(color, Offset(3*u, 9*u), Size(18*u, 10*u), CornerRadius(2*u), style = stroke)
                drawPath(Path().apply { moveTo(4*u, 9*u); lineTo(7*u, 4*u); lineTo(17*u, 4*u); lineTo(20*u, 9*u) }, color, style = stroke)
                drawCircle(color, 1.5f*u, Offset(7*u, 14*u)); drawCircle(color, 1.5f*u, Offset(17*u, 14*u))
                line(5f, 19f, 5f, 21f); line(19f, 19f, 19f, 21f)
            }
            PlayerSymbol.Previous, PlayerSymbol.Next -> {
                val previous = symbol == PlayerSymbol.Previous
                val bar = if (previous) 5f else 19f
                line(bar, 5f, bar, 19f)
                drawPath(Path().apply {
                    moveTo((if (previous) 19f else 5f)*u, 5*u)
                    lineTo((if (previous) 8f else 16f)*u, 12*u)
                    lineTo((if (previous) 19f else 5f)*u, 19*u); close()
                }, color)
            }
            PlayerSymbol.Info -> {
                drawCircle(color, 9*u, Offset(12*u, 12*u), style = stroke)
                drawCircle(color, u, Offset(12*u, 7*u)); line(12f, 11f, 12f, 17f)
            }
            PlayerSymbol.Undo -> drawPath(Path().apply {
                moveTo(20*u, 18*u); cubicTo(20*u, 8*u, 10*u, 7*u, 5*u, 11*u)
                moveTo(5*u, 6*u); lineTo(5*u, 11*u); lineTo(10*u, 11*u)
            }, color, style = stroke)
            PlayerSymbol.Range -> {
                drawRoundRect(color, Offset(3*u, 5*u), Size(18*u, 14*u), CornerRadius(2*u), style = stroke)
                line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f); line(12f, 9f, 12f, 15f)
            }
        }
    }
}
