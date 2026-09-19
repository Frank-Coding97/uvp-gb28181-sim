package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 A.2.5.8 设备实时视音频回传通知。
 *
 * ```xml
 * <Notify>
 *   <CmdType>VideoUploadNotify</CmdType>
 *   <SN>12</SN>
 *   <DeviceID>34020000001320000001</DeviceID>
 *   <Time>2026-09-19T08:40:00</Time>
 *   <Longitude>116.404000</Longitude>   <!-- 可选 -->
 *   <Latitude>39.915000</Latitude>      <!-- 可选 -->
 * </Notify>
 * ```
 *
 * ⚠️ **口径说明（标准留白，不是缺口）**：`VideoUploadNotify` 在 2022 **正文里没有对应小节**
 * —— 全书检索「回传」只出现在附录 A.2.5.8 那两行。这是典型的「附录定了报文、
 * 正文没给流程」的 schema-only 通知。所以：
 *
 *  - **报文形态必须照 A.2.5.8**（本文件负责这一点，含 `Time` 必选、经纬度可选）；
 *  - **触发时机没有标准依据** —— 模拟器当前也没有"设备主动把本地视音频回传平台"这条业务
 *    （实时流走平台点播 INVITE、录像走 RecordInfo）。因此本类目前只提供**报文构造能力**，
 *    **尚未接入任何触发点**。接入时必须能说清"设备在什么业务下会主动回传"，
 *    ⛔ 别为了"用上它"随便挂个时机 —— 那会伪造一条标准里没有流程的行为。
 *
 * ⭐ 2026-09-19 新增（原缺口 G-4：此前 `VideoUploadNotify` 在整个仓库 0 命中）。
 */
data class VideoUploadNotify(
    val sn: Int,
    val deviceId: String,
    val timeIso: String,
    val longitude: Double? = null,
    val latitude: Double? = null,
) {
    companion object {
        /** A.2.5.8 的 `CmdType` 固定值。 */
        const val CMD_TYPE = "VideoUploadNotify"

        private val DEVICE_ID_PATTERN = Regex("^[0-9]{20}$")

        /**
         * @param longitude / [latitude] 标准里各自 `minOccurs=0`，**有就发、没有就不发**：
         *   补一个 `0.0` 会把"设备不知道自己在哪"伪造成"设备在几内亚湾"（0,0）。
         *   小数位走 [MobilePositionNotify.formatDouble]（经纬度的单一格式化真源），
         *   与 `MobilePosition` 通知、`BasicParam` 的 2016 回读保持同一串字符。
         */
        fun build(
            sn: Int,
            deviceId: String,
            timeIso: String,
            longitude: Double? = null,
            latitude: Double? = null,
        ): String {
            require(sn >= 1) { "SN must be positive" }
            require(DEVICE_ID_PATTERN.matches(deviceId)) { "DeviceID must be a 20-digit identifier" }
            val lngLine = longitude
                ?.let { "<Longitude>${MobilePositionNotify.formatDouble(it, 6)}</Longitude>\n" }
                ?: ""
            val latLine = latitude
                ?.let { "<Latitude>${MobilePositionNotify.formatDouble(it, 6)}</Latitude>\n" }
                ?: ""
            return """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>$CMD_TYPE</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<Time>$timeIso</Time>
$lngLine$latLine</Notify>
""".replace("\n", "\r\n")
        }

        /**
         * 反向解析（供平台侧实现 / 本仓测试做往返校验）。
         *
         * `Time` 缺失即判整条非法 —— 它是必选，宁可返回 null 让调用方记一条协议错，
         * 也不要造一个"时间未知"的对象让上层去猜。
         */
        fun parse(xml: String): VideoUploadNotify? {
            // ⛔ 不硬编码编码声明前缀：出站字符集按有效版本走（2016 GB2312 / 2022 GB18030），
            //    写死前缀会在切版本时"静默剥不掉"，表现为解析恒 null。用 stripXmlDeclaration。
            val body = stripXmlDeclaration(xml.trim()).trim()
            if (!body.startsWith("<Notify>") || !body.endsWith("</Notify>")) return null
            if (!ManscdpParser.cmdType(body).equals(CMD_TYPE, ignoreCase = true)) return null

            val sn = ManscdpParser.sn(body)?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val deviceId = ManscdpParser.deviceId(body)?.takeIf { DEVICE_ID_PATTERN.matches(it) } ?: return null
            val timeIso = ManscdpParser.tagValue(body, "Time")?.takeIf { it.isNotBlank() } ?: return null

            return VideoUploadNotify(
                sn = sn,
                deviceId = deviceId,
                timeIso = timeIso,
                longitude = ManscdpParser.tagValue(body, "Longitude")?.toDoubleOrNull(),
                latitude = ManscdpParser.tagValue(body, "Latitude")?.toDoubleOrNull(),
            )
        }
    }
}
