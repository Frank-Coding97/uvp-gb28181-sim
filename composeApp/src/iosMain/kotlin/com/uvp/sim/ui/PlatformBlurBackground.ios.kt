package com.uvp.sim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIVisualEffectView

/**
 * iOS actual — real UIVisualEffectView blur, native frosted glass.
 *
 * UIBlurEffectStyleSystemChromeMaterial is the iOS 13+ recommended style
 * for tab-bar / nav-bar chrome (Apple HIG). It adapts to light/dark mode
 * automatically and matches system Music / Fitness tab bars.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformBlurBackground(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        UIKitView(
            factory = {
                val effect = UIBlurEffect.effectWithStyle(
                    UIBlurEffectStyle.UIBlurEffectStyleSystemChromeMaterial
                )
                UIVisualEffectView(effect = effect)
            },
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}
