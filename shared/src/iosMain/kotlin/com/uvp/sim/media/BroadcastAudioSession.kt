package com.uvp.sim.media

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import kotlin.concurrent.Volatile

/**
 * 语音广播下行专用 AVAudioSession 封装(plan §3 模块 B · §5 Q1)。
 *
 * 决策(plan §5):
 * - category = `.playback`(纯播放,不申请 mic 权限)
 * - mode = `.default`(不用 `.voiceChat`,该模式会启回声抑制/DUCK)
 * - options 空(不加 `.mixWithOthers`,让 iOS 按默认 audio interruption 策略走)
 * - `setActive(true, .notifyOthersOnDeactivation)`
 *
 * 生命周期:
 * - `activate` 首次调用触发 category/mode 配置 + setActive;后续调用幂等
 * - `deactivate` 释放,setActive(false, .notifyOthersOnDeactivation)
 * - `onEnterBackground` 由 [IosAppHost] 观察 `UIApplicationDidEnterBackground` 通知调用
 * - `onEnterForeground` 目前 no-op,等平台重发 Broadcast MESSAGE(Q6 决策一致)
 *
 * 失败一律吞掉 打 Warning,不 crash(plan §6 R5 兜底)。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object BroadcastAudioSession {

    @Volatile
    private var _isActive: Boolean = false

    val isActive: Boolean
        get() = _isActive

    /**
     * 激活 AVAudioSession。首次调用配 category/mode,后续调用幂等直接返回 true。
     *
     * @return true 若成功(含幂等 no-op);false 若 iOS API 抛错
     */
    fun activate(sampleRate: Int, channels: Int): Boolean {
        if (_isActive) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "BROADCAST_AUDIO_SESSION_ACTIVATE_IDEMPOTENT sr=$sampleRate ch=$channels",
            )
            return true
        }
        return runCatching {
            val session = AVAudioSession.sharedInstance()
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                // .playback + defaultToSpeaker → 外放扬声器,不走听筒。
                val cat = session.setCategory(
                    category = AVAudioSessionCategoryPlayback,
                    mode = AVAudioSessionModeDefault,
                    // DefaultToSpeaker 只对 PlayAndRecord 生效;Playback 本身走扬声器。
                    options = 0u,
                    error = err.ptr,
                )
                if (!cat) {
                    val desc = err.value?.localizedDescription ?: "unknown"
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "BROADCAST_AUDIO_SESSION_SET_CATEGORY_FAILED reason=$desc",
                    )
                    return@runCatching false
                }
                val err2 = alloc<ObjCObjectVar<NSError?>>()
                val ok = session.setActive(
                    active = true,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = err2.ptr,
                )
                if (!ok) {
                    val desc = err2.value?.localizedDescription ?: "unknown"
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "BROADCAST_AUDIO_SESSION_SET_ACTIVE_FAILED reason=$desc",
                    )
                    return@runCatching false
                }
            }
            _isActive = true
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "BROADCAST_AUDIO_SESSION_ACTIVATE_OK sr=$sampleRate ch=$channels",
            )
            true
        }.getOrElse { e ->
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "BROADCAST_AUDIO_SESSION_ACTIVATE_EXCEPTION ${e::class.simpleName}: ${e.message}",
            )
            false
        }
    }

    /**
     * 停用 AVAudioSession。若未激活直接 no-op。
     */
    fun deactivate() {
        if (!_isActive) return
        runCatching {
            val session = AVAudioSession.sharedInstance()
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val ok = session.setActive(
                    active = false,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = err.ptr,
                )
                if (!ok) {
                    val desc = err.value?.localizedDescription ?: "unknown"
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "BROADCAST_AUDIO_SESSION_DEACTIVATE_FAILED reason=$desc",
                    )
                }
            }
            _isActive = false
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_DEACTIVATE_OK")
        }.onFailure { e ->
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "BROADCAST_AUDIO_SESSION_DEACTIVATE_EXCEPTION ${e::class.simpleName}: ${e.message}",
            )
            _isActive = false
        }
    }

    /**
     * app 进入后台:被 [IosAppHost] 的 `UIApplicationDidEnterBackground` 观察者调。
     * 语义与 [deactivate] 相同(切后台停播,plan §5 Q6 决策)。
     */
    fun onEnterBackground() {
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_ENTER_BG")
        deactivate()
    }

    /**
     * app 回到前台:no-op。等平台重发 Broadcast MESSAGE 重建 dialog(plan §5 Q6)。
     */
    fun onEnterForeground() {
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "BROADCAST_AUDIO_SESSION_ENTER_FG")
    }

    /** 单测 hook — 重置 [_isActive] 到未激活状态(不动 iOS 侧 session)。 */
    internal fun resetForTest() {
        _isActive = false
    }
}
