package com.uvp.sim.camera

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
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLock

internal class AudioSessionLeaseCounter {
    var count: Int = 0
        private set

    fun acquire(): Boolean {
        count += 1
        return count == 1
    }

    fun release(): Boolean {
        if (count == 0) return false
        count -= 1
        return count == 0
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object UplinkAudioSession {
    private val lock = NSLock()
    private val leases = AudioSessionLeaseCounter()

    fun acquire(): Boolean {
        lock.lock()
        try {
            if (!leases.acquire()) return true
            val activated = configureAndActivate()
            if (!activated) leases.release()
            return activated
        } finally {
            lock.unlock()
        }
    }

    fun release() {
        lock.lock()
        try {
            if (!leases.release()) return
            val session = AVAudioSession.sharedInstance()
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val ok = session.setActive(
                    active = false,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = error.ptr,
                )
                if (!ok) {
                    SystemLogger.emit(
                        LogLevel.Warning,
                        LogTag.Media,
                        "IOS_UPLINK_AUDIO_SESSION_DEACTIVATE_FAILED " +
                            (error.value?.localizedDescription ?: "unknown"),
                    )
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun configureAndActivate(): Boolean {
        val session = AVAudioSession.sharedInstance()
        return memScoped {
            val categoryError = alloc<ObjCObjectVar<NSError?>>()
            val categorySet = session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = categoryError.ptr,
            )
            if (!categorySet) {
                SystemLogger.emit(
                    LogLevel.Error,
                    LogTag.Media,
                    "IOS_UPLINK_AUDIO_SESSION_CATEGORY_FAILED " +
                        (categoryError.value?.localizedDescription ?: "unknown"),
                )
                return@memScoped false
            }

            val activeError = alloc<ObjCObjectVar<NSError?>>()
            val active = session.setActive(active = true, error = activeError.ptr)
            if (!active) {
                SystemLogger.emit(
                    LogLevel.Error,
                    LogTag.Media,
                    "IOS_UPLINK_AUDIO_SESSION_ACTIVATE_FAILED " +
                        (activeError.value?.localizedDescription ?: "unknown"),
                )
                return@memScoped false
            }
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "IOS_UPLINK_AUDIO_SESSION_ACTIVE")
            true
        }
    }
}
