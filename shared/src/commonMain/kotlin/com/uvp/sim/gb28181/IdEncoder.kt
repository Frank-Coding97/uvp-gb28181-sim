package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogNodeType

/**
 * GB/T 28181-2022 §A.3 国标 20 位编码工具。
 *
 * 编码结构(共 20 位):
 *   [0..9]   前缀: 行政区划(8 位) + 行业编码(2 位) — 直接取 SimConfig.server.domain
 *   [10..12] 类型码 (3 位,见 CatalogNodeType.typeCode)
 *   [13..19] 序号 (7 位,左侧补 0)
 *
 * 默认 domain `3402000000` = 浙江省(34020000) + 社会管理(00)。
 */
object IdEncoder {

    fun genChildId(domain: String, type: CatalogNodeType, seq: Int): String {
        val prefix = domain.take(10).padEnd(10, '0')
        val seqStr = seq.toString().padStart(7, '0')
        return prefix + type.typeCode + seqStr
    }

    /**
     * 提取 20 位编码的类型码(第 11-13 位,即索引 10..12)。
     * 非 20 位输入返回 null。
     */
    fun parseTypeCode(id: String): String? {
        if (id.length != 20) return null
        return id.substring(10, 13)
    }

    /**
     * GB/T 28181-2022 §A.3 校验:20 位、全数字。
     * 用于 deviceId / videoChannelId / alarmChannelId / serverId 等"GB ID 字段"。
     * 不校验类型码段或行业码段,只保证长度 + 字符集 — 平台兜底仍由上级编码规则约束。
     */
    fun isValidGbId(id: String): Boolean =
        id.length == 20 && id.all { it in '0'..'9' }

    /**
     * SIP domain / realm 校验:GB §A.3 前 10 位前缀(行政区划 8 + 行业 2),全数字。
     */
    fun isValidGbDomain(domain: String): Boolean =
        domain.length == 10 && domain.all { it in '0'..'9' }
}
