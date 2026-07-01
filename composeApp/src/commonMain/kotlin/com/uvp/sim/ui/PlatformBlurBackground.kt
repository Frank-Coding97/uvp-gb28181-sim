package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Frosted-glass background for the iOS 26 Liquid Glass floating tab bar.
 *
 * - iOS: uses UIVisualEffectView (UIBlurEffect.systemChromeMaterial) via
 *   UIKitView interop for a true native frosted glass.
 * - Android / JVM: falls back to a semi-transparent Surface colour so the
 *   Composable is a no-op on non-iOS platforms.
 *
 * The container fills [modifier] entirely — put Compose children inside
 * [content] as if painting on top of the blur.
 */
@Composable
expect fun PlatformBlurBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
