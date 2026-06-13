package com.uvp.sim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun RecordingThumbnail(
    filePath: String?,
    modifier: Modifier,
    onMissing: @Composable () -> Unit
) {
    Box(modifier = modifier) { onMissing() }
}
