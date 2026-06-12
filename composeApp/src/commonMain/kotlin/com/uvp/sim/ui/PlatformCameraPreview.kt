package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Cross-platform host for the live camera preview surface.
 *
 * - Android: wraps a CameraX [androidx.camera.view.PreviewView] and binds it
 *   to the streamer registered via the global preview registry.
 * - iOS / JVM: returns an empty composable (the caller still wraps it in a
 *   placeholder Box, so the screen never collapses).
 */
@Composable
expect fun PlatformCameraPreview(modifier: Modifier = Modifier)
