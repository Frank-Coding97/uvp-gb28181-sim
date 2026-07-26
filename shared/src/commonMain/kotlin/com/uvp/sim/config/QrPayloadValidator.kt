package com.uvp.sim.config

import com.uvp.sim.gb28181.IdEncoder

/**
 * 扫码六元组落盘前的完整校验(plan §5.7)。
 *
 * 纯函数,返回中文错误消息;`null` 表示全部通过。全通过才**一次性**落盘,
 * 避免部分字段写入留下半截配置。
 *
 * 只校验"值本身是否合规",不判断 host 是否可信 —— 后者由确认页交给人拍板(plan §5.5)。
 */
object QrPayloadValidator {

    fun validate(payload: QrProvisionPayload): String? {
        if (!IdEncoder.isValidGbId(payload.serverId)) {
            return "平台编码(ServerID)必须是 20 位数字"
        }
        if (!IdEncoder.isValidGbDomain(payload.domain)) {
            return "SIP 域必须是 10 位数字"
        }
        if (payload.ip.isBlank()) {
            return "平台 IP 不能为空"
        }
        if (payload.port !in 1..65535) {
            return "平台端口必须在 1-65535 之间"
        }
        if (parseTransport(payload.transport) == null) {
            return "传输协议只支持 UDP 或 TCP"
        }
        if (payload.password.isBlank()) {
            return "SIP 密码不能为空"
        }
        return null
    }

    /**
     * 平台字符串 → [com.uvp.sim.network.TransportType],严格枚举、大小写不敏感。
     * 未知值返回 `null`(**不静默回落 UDP** —— 悄悄改协议会让用户查半天注册失败)。
     */
    fun parseTransport(raw: String): com.uvp.sim.network.TransportType? =
        when (raw.trim().lowercase()) {
            "udp" -> com.uvp.sim.network.TransportType.UDP
            "tcp" -> com.uvp.sim.network.TransportType.TCP
            else -> null
        }
}
