package com.byd.tripstats.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "#123" — a trip's or charging session's id, in the slot just right of a history card's
 * selection checkbox. That checkbox slot is reserved whether or not selection mode shows the
 * box, and this one has a fixed width too, so the id never moves when selection starts and the
 * header lines up from card to card whatever the id's length.
 */
@Composable
fun RecordIdLabel(id: Long, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = "#$id",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}
