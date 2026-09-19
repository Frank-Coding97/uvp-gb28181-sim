package com.uvp.sim.sip

import com.uvp.sim.config.GbVersion

/**
 * GB/T 28181-2022 附录 I「协议版本标识」的版本解析与协商。
 *
 * 标准原文（附录 I，规范性）:
 * > 为便于联网设备或服务器之间互相识别对方支持的协议版本,在 SIP 注册及其响应消息
 * > (无论是成功或失败)头部带上扩展字段 X-GB-Ver 用于表示版本号。双方在注册过程中得知
 * > 对方支持的协议版本后,后续交互过程中**协议版本更高一方应避免向对方发送不能识别的消息**。
 *
 * 表 I.1 版本号定义:1.0=GB/T 28181-2011、1.1=2011 修改补充文件、2.0=2016、3.0=2022。
 *
 * 两条由此推出的规则（本文件只负责第 2 条的计算,第 1 条在 [SipRegisterBuilders]）:
 *  1. **注册报文里带的始终是本机声明版本**(表示"我支持什么"),不因对方而改写 —— 双方都得
 *     如实报出自己的档位,协商才有输入。
 *  2. **业务报文的形态取决于"有效版本" = min(双方声明)** —— 谁低听谁的,这样版本更高的一方
 *     自然就不会发出对方识别不了的消息。本仓的实现差异点是:Catalog/DeviceInfo/DeviceStatus/
 *     AlarmStatus 这几个应答的 2022 形态字段。
 *
 * ⚠️ 本仓与平台侧 `server/app/gb28181/protocol/profile.go` 的口径必须一致:2011 的两个版本
 * (1.0/1.1) 走 2016 兼容 profile,2.0→2016,3.0→2022,其余一律"不可识别"。
 */
internal object GbVersionNegotiation {

    /**
     * 解析 X-GB-Ver 原始值。不可识别（空、格式不符、表 I.1 之外的版本）返回 null。
     *
     * 返回 null 时调用方应**按本机版本继续**(不降级),同时留一条日志 —— 对端没声明版本时
     * 无从判断它能不能识别 2022 形态,降级会白白丢掉本机能力。
     */
    fun parse(raw: String?): GbVersion? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(".")
        if (parts.size != 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        if (major < 0 || minor < 0) return null
        return when {
            major == 1 && (minor == 0 || minor == 1) -> GbVersion.V2016
            major == 2 && minor == 0 -> GbVersion.V2016
            major == 3 && minor == 0 -> GbVersion.V2022
            else -> null
        }
    }

    /** 有效版本 = 双方较低者;对端未声明/不可识别时退回本机版本。 */
    fun effective(local: GbVersion, remote: GbVersion?): GbVersion =
        if (remote == null) local else minOf(local, remote)
}
