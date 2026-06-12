package com.uvp.sim.observability

import android.content.Context

/**
 * Android 端会话计数器持久化。
 *
 * 用 SharedPreferences(同步 API,启动期一次性读写,不需要 DataStore 的协程开销)。
 * 平台壳(MainActivity)启动时:
 *   SessionTracker.install(AndroidSessionStore(applicationContext))
 */
class AndroidSessionStore(context: Context) : SessionStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readLastSessionId(): Int = prefs.getInt(KEY_LAST_SESSION_ID, 0)

    override fun writeLastSessionId(id: Int) {
        prefs.edit().putInt(KEY_LAST_SESSION_ID, id).apply()
    }

    companion object {
        private const val PREFS_NAME = "uvp_sim_observability"
        private const val KEY_LAST_SESSION_ID = "last_session_id"
    }
}
