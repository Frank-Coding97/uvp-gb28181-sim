package com.uvp.sim

import com.uvp.sim.app.AppEngine
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.media.BroadcastAudioSession
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol

/**
 * T-E4-1:iOS 前后台切换 → 语音广播自动停播 hook。
 *
 * 挂 `UIApplicationDidEnterBackground` / `UIApplicationWillEnterForeground` 观察者:
 * - didEnterBackground → `AppEngine.stopBroadcast(Local)` + `BroadcastAudioSession.onEnterBackground()`
 * - willEnterForeground → `BroadcastAudioSession.onEnterForeground()`(目前 no-op,不主动重建)
 *
 * plan §5 Q6 决策:v1.3-E 切后台**停播**,不做后台保活;Info.plist 不加 audio background mode。
 *
 * 由 [IosAppHost] 在 `bindLogger()` 或首次组合时 attach 一次。
 * [detach] 是幂等,重启会话安全。
 */
@OptIn(ExperimentalForeignApi::class)
class BroadcastLifecycleObserver(
    private val engine: AppEngine,
    private val scope: CoroutineScope,
) {
    private var bgObserver: NSObjectProtocol? = null
    private var fgObserver: NSObjectProtocol? = null
    private var backgroundTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun attach() {
        if (bgObserver != null || fgObserver != null) return // 幂等
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue

        bgObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = queue,
        ) { _: NSNotification? ->
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "IOS_APP_ENTER_BG → 停 broadcast + deactivate audio session",
            )
            scope.launch {
                runCatching { engine.stopBroadcast(BroadcastEndReason.Local) }
                    .onFailure { e ->
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Lifecycle,
                            "BROADCAST_STOP_ON_BG_FAILED ${e::class.simpleName}: ${e.message}",
                        )
                    }
            }
            engine.onAppBackground()
            scope.launch { com.uvp.sim.camera.CameraSessionKeepalive.stop() }
            beginLivePlaceholderTask()
            BroadcastAudioSession.onEnterBackground()
        }

        fgObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = queue,
        ) { _: NSNotification? ->
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "IOS_APP_ENTER_FG(broadcast 不主动重建,等平台重发 MESSAGE)",
            )
            BroadcastAudioSession.onEnterForeground()
            endLivePlaceholderTask()
            engine.onAppForeground()
            scope.launch { restartCameraKeepalive() }
        }
    }

    fun detach() {
        val center = NSNotificationCenter.defaultCenter
        bgObserver?.let { center.removeObserver(it) }
        fgObserver?.let { center.removeObserver(it) }
        bgObserver = null
        fgObserver = null
        endLivePlaceholderTask()
    }

    private fun beginLivePlaceholderTask() {
        if (!engine.activeLiveStream.value || backgroundTaskId != UIBackgroundTaskInvalid) return
        backgroundTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Lifecycle,
                "IOS_LIVE_PLACEHOLDER_EXPIRED → 主动结束 INVITE 媒体会话",
            )
            scope.launch {
                try {
                    engine.stopStream("iOS background task expired")
                } finally {
                    endLivePlaceholderTask()
                }
            }
        }
    }

    private fun endLivePlaceholderTask() {
        val task = backgroundTaskId
        if (task == UIBackgroundTaskInvalid) return
        backgroundTaskId = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }

    private suspend fun restartCameraKeepalive() {
        val state = engine.state.value
        if (state != com.uvp.sim.sip.SipState.Registered && state != com.uvp.sim.sip.SipState.InCall) return
        val video = engine.config.value.video
        com.uvp.sim.camera.CameraSessionKeepalive.start(
            com.uvp.sim.camera.CaptureConfig(
                widthPx = video.resolution.widthPx,
                heightPx = video.resolution.heightPx,
                frameRate = video.frameRate,
                bitrateBps = video.bitrateKbps * 1_000,
                keyframeIntervalSeconds = video.keyframeIntervalSeconds,
                videoCodec = video.videoCodec,
            )
        )
    }
}
