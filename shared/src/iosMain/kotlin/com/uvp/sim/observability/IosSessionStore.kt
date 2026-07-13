package com.uvp.sim.observability

import platform.Foundation.NSUserDefaults

/**
 * iOS 端会话计数器持久化,行为对齐 [AndroidSessionStore]。
 *
 * 用 [NSUserDefaults]:同步、无协程开销、启动期一次读一次写。
 * 平台壳([com.uvp.sim.IosAppHost])启动时:
 *   SessionTracker.install(IosSessionStore())
 *
 * 冷启动时 SessionTracker 读 [readLastSessionId] + 1 作为新 sessionId,
 * 再回写。老板每次冷启动看到"会话 #3 #4 #5..."单调递增。
 */
class IosSessionStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SessionStore {

    override fun readLastSessionId(): Int =
        defaults.integerForKey(KEY_LAST_SESSION_ID).toInt()

    override fun writeLastSessionId(id: Int) {
        defaults.setInteger(id.toLong(), KEY_LAST_SESSION_ID)
        defaults.synchronize()
    }

    companion object {
        internal const val KEY_LAST_SESSION_ID = "com.uvp.sim.observability.last_session_id"
    }
}
