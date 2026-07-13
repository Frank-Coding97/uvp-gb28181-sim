package com.uvp.sim

import androidx.compose.ui.uikit.OnFocusBehavior
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
    // OnFocusBehavior.DoNothing: 关掉 Compose Multiplatform 默认的"焦点整体上推"
    // 策略,改由 UI 侧的 Modifier.imePadding()(见 App.kt)接管键盘避让,避免
    // 悬浮 tab bar 被顶到屏幕顶部。SwiftUI 侧同步 .ignoresSafeArea(.keyboard)
    // 关掉 UIKit 的自动 keyboard avoidance —— 见 ContentView.swift。
    val controller = ComposeUIViewController(configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
    }) {
        IosApp()
    }
    // 冷启动首帧防蓝色透出 - Compose 首帧渲染完成前, controller.view 默认背景可能
    // 是 UIColor.clearColor / systemBackground, 会透出下层 SwiftUI window 或系统
    // launch screen 残影。显式铺白让 iOS 系统 splash 撤下到 Compose 品牌屏首帧
    // 之间任何一帧都是白色, 消除观感"蓝色一闪"。
    controller.view.setBackgroundColor(platform.UIKit.UIColor.whiteColor)
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
