package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.lonode.debridbooks.R

/** Provider file names and tracker release titles are different; neither is cleaned for display. */
@Composable
fun ReleaseFilename(filename: String?) {
    OriginalName(filename, stringResource(R.string.full_filename))
}

@Composable
fun ReleaseName(name: String?) {
    OriginalName(name, stringResource(R.string.original_release_name))
}

@Composable
private fun OriginalName(value: String?, label: String) {
    if (!value.isNullOrBlank()) Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer(Modifier.fillMaxWidth()) {
            Text(value, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                softWrap = true, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        }
    }
}
