package com.uvp.sim.observability

/**
 * GB28181 编码规则(GB/T 28181-2022 §6.1.2 / §6.1.4):
 *
 * 20 位 ID = 行政区划(6) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(8)
 *
 * 类型编码(常见运维场景):
 *   132 视频通道  · 134 报警通道  · 136 音频通道
 *   200 平台      · 199 业务平台
 *   111 设备(带摄像头) · 118 设备(NVR / 编码设备)
 *
 * 真机现场,运维需要的不是 channel.takeLast(3) 那种"通道 001"
 * (因为末 3 位常常都是 001/002,识别度低),而是
 *   - 类型 ("视频"/"报警"/"设备")
 *   - 序号末 4 位(同类型内可区分)
 */
object Gb28181IdParser {

    data class ParsedId(
        val typeCode: String,
        val typeName: String,
        val serialShort: String,
        val label: String
    )

    fun parse(id: String): ParsedId? {
        if (id.length != 20) return null
        if (!id.all { it.isDigit() }) return null
        val typeCode = id.substring(10, 13)
        val serial = id.substring(14)
        val serialShort = serial.takeLast(4)
        val typeName = TYPE_NAMES[typeCode] ?: "类型 $typeCode"
        val label = when (typeCode) {
            "132" -> "视频通道 $serialShort"
            "134" -> "报警通道 $serialShort"
            "136" -> "音频通道 $serialShort"
            "200", "199" -> "平台 $serialShort"
            "111", "118" -> "设备 $serialShort"
            else -> "类型 $typeCode · $serialShort"
        }
        return ParsedId(typeCode, typeName, serialShort, label)
    }

    fun parseFromRequestUri(uri: String): ParsedId? {
        if (uri.isEmpty()) return null
        val idPart = uri.removePrefix("sip:").substringBefore('@')
        return parse(idPart)
    }

    private val TYPE_NAMES = mapOf(
        "132" to "视频",
        "134" to "报警",
        "136" to "音频",
        "200" to "平台",
        "199" to "平台",
        "111" to "设备",
        "118" to "设备"
    )
}
