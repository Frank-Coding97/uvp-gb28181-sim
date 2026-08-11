package com.uvp.sim.gb28181

/** GB/T 28181-2022 A.2.6.15 PTZ 精准位置标准状态字段。 */
data class PtzPositionSnapshot(
    val pan: Double,
    val tilt: Double,
    val zoom: Double,
    val horizontalFieldAngle: Double,
    val verticalFieldAngle: Double,
    val maxViewDistance: Double,
)

/** GB/T 28181-2022 9.11.2.3 PTZ 精准位置变化 NOTIFY 的 MANSCDP 消息体。 */
data class PtzPositionMessage(
    val sn: Int,
    val deviceId: String,
    val position: PtzPositionSnapshot,
) {
    companion object {
        private const val CMD_TYPE = "PTZPosition"
        private val DEVICE_ID_PATTERN = Regex("^[0-9]{20}$")

        fun build(sn: Int, deviceId: String, position: PtzPositionSnapshot): String {
            require(sn >= 1) { "SN must be positive" }
            require(DEVICE_ID_PATTERN.matches(deviceId)) { "DeviceID must be a 20-digit identifier" }
            require(position.isFinite()) { "PTZPosition values must be finite" }

            return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>$CMD_TYPE</CmdType>
<SN>$sn</SN>
<DeviceID>${escapeXmlText(deviceId)}</DeviceID>
<Pan>${formatTwo(position.pan)}</Pan>
<Tilt>${formatTwo(position.tilt)}</Tilt>
<Zoom>${formatTwo(position.zoom)}</Zoom>
<HorizontalFieldAngle>${formatTwo(position.horizontalFieldAngle)}</HorizontalFieldAngle>
<VerticalFieldAngle>${formatTwo(position.verticalFieldAngle)}</VerticalFieldAngle>
<MaxViewDistance>${formatTwo(position.maxViewDistance)}</MaxViewDistance>
</Response>
""".replace("\n", "\r\n")
        }

        /**
         * A.2.6.15 的 Schema 将姿态字段标为可选；本模拟器的订阅通知契约要求六项齐全，
         * 避免平台收到部分状态却把它误判为完整精准位置。
         */
        fun parse(xml: String): PtzPositionMessage? {
            val body = xml.trim()
                .removePrefix("<?xml version=\"1.0\" encoding=\"GB2312\"?>")
                .trim()
            if (!body.startsWith("<Response>") || !body.endsWith("</Response>")) return null
            if (!ManscdpParser.cmdType(body).equals(CMD_TYPE, ignoreCase = true)) return null

            val sn = ManscdpParser.sn(body)?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val deviceId = ManscdpParser.deviceId(body)?.takeIf { DEVICE_ID_PATTERN.matches(it) } ?: return null
            val position = PtzPositionSnapshot(
                pan = body.doubleTag("Pan") ?: return null,
                tilt = body.doubleTag("Tilt") ?: return null,
                zoom = body.doubleTag("Zoom") ?: return null,
                horizontalFieldAngle = body.doubleTag("HorizontalFieldAngle") ?: return null,
                verticalFieldAngle = body.doubleTag("VerticalFieldAngle") ?: return null,
                maxViewDistance = body.doubleTag("MaxViewDistance") ?: return null,
            )
            return PtzPositionMessage(sn, deviceId, position.takeIf { it.isFinite() } ?: return null)
        }

        private fun String.doubleTag(name: String): Double? =
            ManscdpParser.tagValue(this, name)?.toDoubleOrNull()

        private fun formatTwo(value: Double): String {
            val rounded = kotlin.math.round(value * 100.0).toLong()
            val sign = if (rounded < 0) "-" else ""
            val absolute = kotlin.math.abs(rounded)
            return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
        }
    }
}

private fun PtzPositionSnapshot.isFinite(): Boolean =
    pan.isFinite() && tilt.isFinite() && zoom.isFinite() &&
        horizontalFieldAngle.isFinite() && verticalFieldAngle.isFinite() && maxViewDistance.isFinite()
