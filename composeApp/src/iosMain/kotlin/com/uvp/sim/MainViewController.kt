package com.uvp.sim

import androidx.compose.ui.window.ComposeUIViewController
import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.darwin.NSObject

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
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController {
        IosApp()
    }
    val target = TapLoggerTarget(viewProvider = { controller.view }).also {
        RootTouchLoggerRegistry.retain(it)
    }
    val tap = UITapGestureRecognizer(
        target = target,
        action = NSSelectorFromString("onTap:")
    ).apply {
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }
    controller.view.addGestureRecognizer(tap)
    return controller
}

private object RootTouchLoggerRegistry {
    private val retainedTargets = mutableListOf<Any>()
    fun retain(target: Any) {
        retainedTargets += target
    }
}

@OptIn(ExperimentalForeignApi::class)
@ExportObjCClass
private class TapLoggerTarget(
    private val viewProvider: () -> UIView?,
) : NSObject() {
    @ObjCAction
    fun onTap(recognizer: UITapGestureRecognizer) {
        val hostView = viewProvider() ?: return
        val point = recognizer.locationInView(hostView)
        val hit = hostView.hitTest(point, withEvent = null)
        val (x, y) = point.useContents { x to y }
        SystemLogger.emit(
            LogLevel.Debug,
            LogTag.Media,
            "IOS_UI_ROOT_TAP x=$x y=$y hit=${hit?.description ?: "nil"}"
        )
    }
}
