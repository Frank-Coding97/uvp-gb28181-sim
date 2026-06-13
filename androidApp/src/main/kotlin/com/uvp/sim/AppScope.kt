package com.uvp.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级 CoroutineScope — 录像 service / 前台 service 用,
 * 不跟 Activity 生命周期绑定,Activity 重建时仍存活。
 *
 * 进程死了 scope 自然死;不需要手动 cancel。
 */
object AppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
