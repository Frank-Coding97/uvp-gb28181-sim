package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 §9.3.4 A.2.3.1.11 PTZPreciseCtrl 精确云台控制解析.
 *
 * 平台下发示例:
 *
 * ```xml
 * <Control>
 *   <CmdType>DeviceControl</CmdType>
 *   <SN>1</SN>
 *   <DeviceID>34020000001320000001</DeviceID>
 *   <PTZPreciseCtrl>
 *     <Pan>123.45</Pan>      <!-- 0~360.00 水平角度 -->
 *     <Tilt>-15.00</Tilt>    <!-- -30~90 垂直角度 -->
 *     <Zoom>3.50</Zoom>      <!-- 倍率,>=1.00 -->
 *   </PTZPreciseCtrl>
 * </Control>
 * ```
 *
 * 跟 PTZCmd 8 字节方向控制(byte3 bit 拆解)是两套语义:
 * - PTZCmd → 速度 + 方向
 * - PTZPreciseCtrl → 确切角度
 *
 * GB-2022 §7.3 标记为"宜支持". 海康/大华 2022 后新固件已实现,但 WVP-PRO
 * 当前未下发,本轮先解析 + 200 OK + 状态写入备查询(§9.5.3 A.2.4.13).
 */
data class PtzPreciseCtrl(
    val pan: Float,
    val tilt: Float,
    val zoom: Float,
)

object PtzPreciseCtrlParser {

    fun parse(xml: String): PtzPreciseCtrl? {
        val pan = ManscdpParser.tagValue(xml, "Pan")?.toFloatOrNull() ?: return null
        val tilt = ManscdpParser.tagValue(xml, "Tilt")?.toFloatOrNull() ?: return null
        val zoom = ManscdpParser.tagValue(xml, "Zoom")?.toFloatOrNull() ?: return null
        return PtzPreciseCtrl(pan, tilt, zoom)
    }
}
