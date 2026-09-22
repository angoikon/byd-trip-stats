package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.byd.tripstats.ui.theme.ToggleUncheckedTrack

/**
 * The app's switch: a light silver track with a white thumb when off, matching the head unit's own
 * toggles rather than Material's default grey.
 *
 * It exists because this styling was pasted into ten different Settings files, which is a recipe
 * that only works until someone adds the eleventh switch and forgets — as happened with the
 * Tailscale HTTPS toggle, which shipped looking like a different control from the ABRP one beside
 * it. Reach for this instead of a bare [Switch] anywhere in Settings.
 */
@Composable
fun BrandSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        // The inner dot only appears in the off state: it is what makes a silver track read as
        // "off" rather than as a disabled control.
        thumbContent = if (!checked) {
            { Box12(ToggleUncheckedTrack) }
        } else null,
        colors = SwitchDefaults.colors(
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = ToggleUncheckedTrack,
            uncheckedBorderColor = ToggleUncheckedTrack,
        ),
    )
}

@Composable
private fun Box12(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(12.dp).background(color, CircleShape)
    )
}
