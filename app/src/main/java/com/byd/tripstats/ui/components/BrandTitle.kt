package com.byd.tripstats.ui.components

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.util.ScreenshotFlashController
import com.byd.tripstats.util.ScreenshotUtil
import kotlinx.coroutines.launch

/**
 * BYD logo + "trip stats" wordmark shown at the start of every screen's TopAppBar.
 * Tapping it saves a screenshot (Pro feature) — same behavior on every screen, not just Dashboard.
 */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val isPro by EntitlementManager.isPro.collectAsState()
    val logoInteractionSource = remember { MutableInteractionSource() }
    // A Compose Dialog (e.g. the HV/12V battery history) renders in its own window.
    // Capture that window so the screenshot is the dialog content, not the screen behind it.
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    Row(
        modifier = modifier.clickable(
            interactionSource = logoInteractionSource,
            indication = null
        ) {
            if (!isPro) {
                Toast.makeText(context, context.getString(R.string.screenshots_pro_only), Toast.LENGTH_LONG).show()
                return@clickable
            }
            val act = activity ?: return@clickable
            scope.launch {
                try {
                    ScreenshotUtil.captureAndSave(
                        act,
                        window = dialogWindow ?: act.window,
                        onCaptured = { ScreenshotFlashController.flash() },
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.screenshot_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.byd_logo),
            contentDescription = "BYD — tap to save a screenshot",
            modifier = Modifier.height(28.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "trip stats",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Standard TopAppBar navigationIcon: brand always at the far left, then the
 * screen's own back/close button — so the brand can't be mistaken for the back tap target.
 */
@Composable
fun BrandNavigationBar(
    modifier: Modifier = Modifier,
    dividerColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
    showTrailingDivider: Boolean = false,
    navigationIcon: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandTitle()
        if (navigationIcon != null || showTrailingDivider) {
            VerticalDivider(
                modifier = Modifier.padding(horizontal = 12.dp).height(24.dp),
                thickness = 1.dp,
                color = dividerColor
            )
            navigationIcon?.invoke()
        }
    }
}

/** Full-screen white flash played right after a screenshot is captured; mounted once at the app root. */
@Composable
fun ScreenshotFlashOverlay() {
    val visible by ScreenshotFlashController.visible.collectAsState()
    if (!visible) return
    val alpha = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(0f, animationSpec = tween(durationMillis = 320, easing = LinearEasing))
        ScreenshotFlashController.reset()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = alpha.value))
    )
}
