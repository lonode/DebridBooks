package io.github.lonode.debridbooks.comics

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

internal enum class ReaderTap { Previous, Next, Controls, None }

internal object ReaderGesturePolicy {
    fun inside(x: Float, width: Float, left: Float, right: Float): Boolean = x >= left && x < width - right
    fun tap(x: Float, width: Float, left: Float, right: Float, paged: Boolean): ReaderTap {
        if (!inside(x, width, left, right)) return ReaderTap.None
        return when {
            x < width * .3f -> if (paged) ReaderTap.Previous else ReaderTap.None
            x > width * .7f -> if (paged) ReaderTap.Next else ReaderTap.None
            else -> ReaderTap.Controls
        }
    }
    fun horizontal(x: Float, y: Float, threshold: Float): Boolean = abs(x) > threshold && abs(x) > abs(y) * 1.5f
    fun swipe(x: Float, y: Float, threshold: Float): Int =
        if (!horizontal(x, y, threshold)) 0 else if (x < 0) 1 else -1
}

/** Leave system edges and unzoomed vertical drags unconsumed. Pinches never turn pages. */
internal suspend fun PointerInputScope.detectReaderGestures(
    paged: Boolean, left: Float, right: Float, bottom: Float, swipeThreshold: Float,
    scale: () -> Float, transform: (Float, Offset) -> Unit,
    tap: (ReaderTap) -> Unit, swipe: (Int) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        if (!ReaderGesturePolicy.inside(first.position.x, size.width.toFloat(), left, right) || first.position.y >= size.height - bottom) return@awaitEachGesture
        var movement = Offset.Zero
        var accumulatedZoom = 1f
        var multi = false
        var transformed = false
        var swiping = false
        var moved = false
        var position = first.position
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            if (event.changes.count { it.pressed } >= 2) multi = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            movement += pan
            accumulatedZoom *= zoom
            position = event.changes.firstOrNull { it.id == first.id }?.position ?: position
            moved = moved || movement.getDistance() > viewConfiguration.touchSlop
            if (multi || scale() > 1.01f) {
                val zoomDistance = abs(1 - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                if (transformed || moved || zoomDistance > viewConfiguration.touchSlop) {
                    transformed = true
                    transform(zoom, pan)
                    event.changes.forEach { if (it.pressed || it.previousPressed) it.consume() }
                }
            } else if (paged && (swiping || ReaderGesturePolicy.horizontal(movement.x, movement.y, viewConfiguration.touchSlop))) {
                swiping = true
                event.changes.forEach { if (it.pressed || it.previousPressed) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (!multi && !transformed && swiping) {
            val direction = ReaderGesturePolicy.swipe(movement.x, movement.y, swipeThreshold)
            if (direction != 0) swipe(direction)
        } else if (!multi && !transformed && !moved) {
            tap(ReaderGesturePolicy.tap(position.x, size.width.toFloat(), left, right, paged && scale() <= 1.01f))
        }
    }
}
