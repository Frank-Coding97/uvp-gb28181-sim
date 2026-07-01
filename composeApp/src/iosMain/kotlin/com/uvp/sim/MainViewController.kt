package com.uvp.sim

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * KMP entry point consumed by iosApp/ContentView.swift.
 *
 * Generated Swift binding lives at
 *   MainViewControllerKt.MainViewController()
 *
 * v1.1 status: real App() UI wired via [IosAppHost]. Media pipeline stubs
 * (camera / audio / recording) come from [PlatformRuntimeIos] — real
 * AVCaptureSession / AVAudioEngine wiring lands in T4/T8-follow-up.
 */
@Suppress("FunctionName", "unused")  // Called from Swift via KMP-generated binding
fun MainViewController(): UIViewController = ComposeUIViewController {
    IosApp()
}
