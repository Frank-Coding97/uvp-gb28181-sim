package com.uvp.sim.ui.actions

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * MainActivity slice impl 公用日志 helper(PR-B)。
 *
 * 集中处理 `用户XXX` 系 SystemLogger.emit + 触发 ViewModel 动作的二行模板,
 * 让 4 个 slice impl 不重复 emit 代码。
 *
 * 用法:
 *   override fun onConnect() = logged("用户点击注册") { viewModel.connect() }
 *   override fun onCatalogTreeSave(tree): String? = loggedR("保存目录树") { ... }
 *
 * 设计:emit 在 body 之前(跟原有顺序一致,日志先出再触发动作)。
 */
internal inline fun logged(msg: String, body: () -> Unit) {
    SystemLogger.emit(LogLevel.Info, LogTag.User, msg)
    body()
}

internal inline fun <R> loggedR(msg: String, body: () -> R): R {
    SystemLogger.emit(LogLevel.Info, LogTag.User, msg)
    return body()
}
