package com.uvp.sim.domain

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.ErrorMetrics
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
 * **Wave 5 P3-3**:分级 + metrics:[map] 返回 [UserError](message + category),
 * 调用点拿到 category 后可 fan-out 到 SimEvent.TransportError(可选字段)/ 重试策略;
 * 同时内部自动 `ErrorMetrics.increment(label, category)` 上计数器,UI / 调试可拉 snapshot。
 *
 * ## 用法
 *
 * ```kotlin
 * } catch (e: Throwable) {
 *     val err = mapToUserError("send INVITE", e)
 *     simEventEmit(SimEvent.TransportError(err.message, err.category))
 * }
 * ```
 *
 * 老调用点 `mapToUserError("ctx", e).message` 兼容(`UserError` 有 message 字段)。
 * 注意:该函数同时会 emit DEBUG 级别 SystemLogger 留底,所以调用方不需要再单独打 log。
 */
internal fun mapToUserError(context: String, e: Throwable): UserError =
    UserErrorMapper.map(context, e)

/**
 * Helper:`mapToUserError(ctx, e)` → `SimEvent.TransportError(msg, category)` 的二合一。
 * 调用点用 `simEventEmit(transportErrorOf("ctx", e))` 比手写两步更不容易漏 category。
 *
 * Shared 内部用 internal 版本;跨模块(androidApp / composeApp)用 [simTransportErrorOf]
 * top-level 工厂(下方,public)。
 */
internal fun transportErrorOf(context: String, e: Throwable): SimEvent.TransportError {
    val err = mapToUserError(context, e)
    return SimEvent.TransportError(description = err.message, category = err.category)
}

/**
 * 跨模块入口 — public,供 androidApp / composeApp 用。
 *
 * ```kotlin
 * _events.update { (listOf(simTransportErrorOf("save catalog", e)) + it).take(N) }
 * ```
 */
fun simTransportErrorOf(context: String, e: Throwable): SimEvent.TransportError {
    val err = UserErrorMapper.map(context, e)
    return SimEvent.TransportError(description = err.message, category = err.category)
}

/**
 * 用户可见错误信息 + 分级。
 *
 * - [message]:UI 安全展示串(`"<context>: <ExceptionType>"`,不含内网 IP / 路径 / token)
 * - [category]:错误分级,参见 [ErrorCategory]。重试决策与 metrics 都用这个字段。
 */
data class UserError(
    val message: String,
    val category: ErrorCategory,
) {
    /** 跟旧 `String` 返回值兼容:`mapToUserError(...).toString()` 自动得回 message。 */
    override fun toString(): String = message
}

/**
 * 公开 API:跨模块(如 androidApp / composeApp)调用的入口。`internal` 版本
 * [mapToUserError] 给 shared 模块内部用。
 */
object UserErrorMapper {
    /**
     * 异常 → 错误信息 + 分级。
     *
     * 副作用:
     * 1. 写 DEBUG 级 SystemLogger(含完整 message + cause 链)
     * 2. 自动 `ErrorMetrics.increment(context, category)` — 调用点不需要手动调
     */
    fun map(context: String, e: Throwable): UserError {
        val type = e::class.simpleName ?: "Error"
        val category = categorize(e)
        // 完整 message + cause 链只走 DEBUG 级别 SystemLogger,生产 build 自动过滤掉。
        // 这样开发者在 logcat 仍能拉到全文,但 UI / 用户可见事件流不会泄漏内网信息。
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Network,
            "$context [$type/${category.name}]: ${e.message ?: "<no message>"}" +
                (e.cause?.let { " cause=${it::class.simpleName}: ${it.message}" } ?: "")
        )
        // 上计数器(suspend → 用全局 scope 触发,不阻塞调用方)。
        // 老板原话:"所有 UserErrorMapper.map() 内部自动调 ErrorMetrics.increment"。
        incrementAsync(context, category)
        return UserError(message = "$context: $type", category = category)
    }

    /**
     * 异常 → ErrorCategory 分级。**保守映射**:
     *
     * - Socket / Timeout / Connect / IO 名字带 "Closed"/"Reset" → Transient
     * - SipParseException / MansRtspParseException / 名字含 "Parse" → ProtocolViolation
     * - IllegalArgumentException / 名字含 "InvalidConfig" → UserInput
     * - SecurityException / 名字含 "AuthFailed"/"Unauthorized" / 401/403 类 → Permanent
     * - 其他 IllegalStateException / NullPointerException / IndexOutOfBoundsException → Internal
     * - 任何兜底 → Internal
     *
     * commonMain 不能 import `java.net.*`,所以全靠 simpleName 字符串匹配。
     */
    fun categorize(e: Throwable): ErrorCategory {
        val name = e::class.simpleName ?: return ErrorCategory.Internal

        // ProtocolViolation:对端不遵协议
        if (name.contains("Parse", ignoreCase = true)) return ErrorCategory.ProtocolViolation
        if (name == "SipParseException" || name == "MansRtspParseException") {
            return ErrorCategory.ProtocolViolation
        }

        // Transient:网络抖动 / socket 暂闭 / 超时 — 重试可能恢复
        if (name == "SocketTimeoutException") return ErrorCategory.Transient
        if (name == "SocketException") return ErrorCategory.Transient
        if (name == "ConnectException") return ErrorCategory.Transient
        if (name == "UnknownHostException") return ErrorCategory.Transient
        if (name == "ClosedChannelException") return ErrorCategory.Transient
        if (name == "InterruptedIOException") return ErrorCategory.Transient
        // IOException 兜底:网络 / 磁盘 IO 多数可重试,但要排除 Parse 类(上面已先匹配)
        if (name == "IOException" || name == "EOFException") return ErrorCategory.Transient

        // Permanent:认证拒绝 / 协议不兼容 / 配置非法导致的"重试无意义"
        if (name == "SecurityException") return ErrorCategory.Permanent
        if (name.contains("Unauthorized", ignoreCase = true)) return ErrorCategory.Permanent
        if (name.contains("AuthFailed", ignoreCase = true)) return ErrorCategory.Permanent
        if (name.contains("Forbidden", ignoreCase = true)) return ErrorCategory.Permanent

        // UserInput:配置缺字段 / 输入格式非法
        if (name == "IllegalArgumentException") return ErrorCategory.UserInput
        if (name == "NumberFormatException") return ErrorCategory.UserInput
        if (name.contains("InvalidConfig", ignoreCase = true)) return ErrorCategory.UserInput

        // Internal:我方代码 bug — 默认兜底
        if (name == "IllegalStateException") return ErrorCategory.Internal
        if (name == "NullPointerException") return ErrorCategory.Internal
        if (name == "IndexOutOfBoundsException") return ErrorCategory.Internal
        if (name == "ClassCastException") return ErrorCategory.Internal
        if (name == "UnsupportedOperationException") return ErrorCategory.Internal

        // 兜底 → Internal(异常类型未知,按"我方 bug 不知怎么回事"对待)
        return ErrorCategory.Internal
    }

    /**
     * Mutex 串行的 [ErrorMetrics.increment] 是 suspend,这里用 GlobalScope 触发一个轻量任务。
     *
     * - 单次 increment 实质只是占用一次 mutex + map put,微秒级
     * - GlobalScope 暴露在 KMP 全平台都有(commonMain 可用)
     * - 不抛回调用方:metrics 失败也不该影响业务路径
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun incrementAsync(label: String, category: ErrorCategory) {
        // 用 Dispatchers.Unconfined 把任务"投出去就忘",mutex 自身做串行保护
        GlobalScope.launch(Dispatchers.Unconfined) {
            ErrorMetrics.increment(label, category)
        }
    }
}
