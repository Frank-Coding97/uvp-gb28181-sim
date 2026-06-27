package com.uvp.sim.network

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * M-6 (audit §3) — 服务器 IP 白名单强制工具。
 *
 * UDP / TCP transport 在 connect 前调 [enforce] 检查目标 IP 是否在白名单。
 * 空白名单 = 不强制(默认行为,保兼容)。命中 = 通过;非空但未命中 = throw
 * + SystemLogger Error,connect 直接失败。
 *
 * v1 只匹配精确字符串(IP 字面量比对),不解析 CIDR / DNS,避免引入 IP
 * 解析库依赖。LAN 联调场景多数已知平台 IP,精确匹配够用。
 */
object ServerAllowList {

    /**
     * @return true = 通过(白名单空或命中);false = 拒绝(已记录 SystemLogger Error)
     */
    fun check(host: String, allowList: List<String>): Boolean {
        if (allowList.isEmpty()) return true
        if (host in allowList) return true
        SystemLogger.emit(
            LogLevel.Error,
            LogTag.Network,
            "服务器 IP $host 不在白名单 $allowList — 拒绝 connect",
            category = ErrorCategory.Permanent,
        )
        return false
    }

    /**
     * 跟 [check] 同款语义,但拒绝时 throw — 给 transport.connect() 直接抛回上层。
     */
    fun enforce(host: String, allowList: List<String>) {
        if (!check(host, allowList)) {
            throw ServerNotAllowedException(host, allowList)
        }
    }
}

/** [ServerAllowList] 拒绝 connect 时抛出。commonMain 无 SecurityException,用 IllegalStateException。 */
class ServerNotAllowedException(
    val host: String,
    val allowList: List<String>,
) : IllegalStateException(
    "服务器 IP $host 不在配置的白名单($allowList) — connect 被拒"
)
