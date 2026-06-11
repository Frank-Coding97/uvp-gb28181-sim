package com.uvp.sim.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol

/**
 * iOS app lifecycle observer (T14).
 *
 * Bridges UIApplicationDidEnterBackgroundNotification +
 * UIApplicationWillEnterForegroundNotification to a Kotlin Flow so the
 * SimulatorEngine can react (e.g. unregister 30 s after going background).
 *
 * The corresponding Android lifecycle is observed at the SipViewModel layer
 * via androidx.lifecycle. iOS doesn't have a built-in cross-platform Compose
 * lifecycle, so we wire it manually here.
 *
 * Per spec v1 §4 M1 + plan v1 §10:
 *   - Going to background → start 30 s timer → unregister + close transport
 *   - Returning to foreground within 30 s → cancel timer
 *   - Returning after 30 s → reconnect (caller decides; this class only
 *     emits the lifecycle events)
 */
class IosAppLifecycle {

    private val _events = MutableSharedFlow<AppLifecycleEvent>(extraBufferCapacity = 8)
    val events: Flow<AppLifecycleEvent> = _events.asSharedFlow()

    private var bgObserver: NSObjectProtocol? = null
    private var fgObserver: NSObjectProtocol? = null

    fun observe(scope: CoroutineScope) {
        val center = NSNotificationCenter.defaultCenter
        val mainQueue = NSOperationQueue.mainQueue
        bgObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = mainQueue
        ) { _ ->
            _events.tryEmit(AppLifecycleEvent.DidEnterBackground)
        }
        fgObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = mainQueue
        ) { _ ->
            _events.tryEmit(AppLifecycleEvent.WillEnterForeground)
        }
    }

    fun stop() {
        val center = NSNotificationCenter.defaultCenter
        bgObserver?.let { center.removeObserver(it) }
        fgObserver?.let { center.removeObserver(it) }
        bgObserver = null
        fgObserver = null
    }
}

/** Cross-platform app lifecycle event for the engine to react to. */
sealed class AppLifecycleEvent {
    object DidEnterBackground : AppLifecycleEvent()
    object WillEnterForeground : AppLifecycleEvent()
}
