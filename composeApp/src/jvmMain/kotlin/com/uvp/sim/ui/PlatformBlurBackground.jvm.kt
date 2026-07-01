package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformBlurBackground(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.background(Color.White.copy(alpha = 0.95f))) {
        content()
    }
}
