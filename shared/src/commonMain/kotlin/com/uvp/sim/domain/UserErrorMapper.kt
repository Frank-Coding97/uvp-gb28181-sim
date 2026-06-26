package com.uvp.sim.domain

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * H-4 (PR-SEC-1 / security-audit §2):统一异常 → 用户可见错误字符串映射器。
 *
 * **背景**:旧代码遍布 `SimEvent.TransportError("ctx: ${e.message}")` 模式。
 * Ktor / Socket / OkHttp 等底层异常 `e.message` 经常带:
 *
 *   - 内网 IP 端口(如 `Connect failed: 192.168.10.222:8160 timeout`)
 *   - 本地路径(如 `/data/data/com.uvp.sim/cache/xxx`)
 *   - 协议栈内部 trace(如 `ChannelClosed { ... }`)
 *
 * 直接拼到 [SimEvent.TransportError.description] 后:
 *   1. 进 UI 事件列表 → 老板截屏发群 → 泄漏内网拓扑
 *   2. 进 logcat → 接入其他 app 时可被采集 → 同样泄漏
 *
 * **修复方案**:UI 层只看到异常 *类型*(`UnknownHostException` / `SocketTimeoutException`
 * 等),完整 message 仅在 [SystemLogger] DEBUG 级别打,在线分析时由用户手动开。
 *
 * ## 用法
 *
 * ```kotlin
 * } catch (e: Throwable) {
 *     simEventEmit(SimEvent.TransportError(mapToUserError("send INVITE", e)))
 * }
 * ```
 *
 * 注意:该函数同时会 emit DEBUG 级别 SystemLogger 留底,所以调用方不需要再单独打 log。
 */
internal fun mapToUserError(context: String, e: Throwable): String =
    UserErrorMapper.map(context, e)

/**
 * 公开 API:跨模块(如 androidApp / composeApp)调用的入口。`internal` 版本
 * [mapToUserError] 给 shared 模块内部用。
 */
object UserErrorMapper {
    fun map(context: String, e: Throwable): String {
        val type = e::class.simpleName ?: "Error"
        // 完整 message + cause 链只走 DEBUG 级别 SystemLogger,生产 build 自动过滤掉。
        // 这样开发者在 logcat 仍能拉到全文,但 UI / 用户可见事件流不会泄漏内网信息。
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Network,
            "$context [$type]: ${e.message ?: "<no message>"}" +
                (e.cause?.let { " cause=${it::class.simpleName}: ${it.message}" } ?: "")
        )
        return "$context: $type"
    }
}
