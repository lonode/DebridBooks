package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.R

@Composable
fun ClearSearchButton(onClear: () -> Unit) {
    val label = stringResource(R.string.clear_search)
    IconButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = label }) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(20.dp)) {
            val inset = 4.dp.toPx()
            drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
