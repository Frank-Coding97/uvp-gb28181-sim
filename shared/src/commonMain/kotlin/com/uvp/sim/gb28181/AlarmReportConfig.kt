package com.uvp.sim.gb28181

import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 **A.2.1.18 报警上报开关配置类型**（`alarmReportCfgType`）的当前取值。
 *
 * 只有两个字段，都是**必选 integer**（0-关闭 / 1-打开）：
 *   - [motionDetection] `MotionDetection` 移动侦测事件上报开关
 *   - [fieldDetection]  `FieldDetection`  区域入侵事件上报开关
 *
 * ⚠️ 本仓前端 `deviceConfigGroups.ts` 把这个组写成「视频报警 / 设备报警」—— 那是**语义全错**
 * 的字段名（既不是标准字段名，也不是标准语义）。以本标准原文为准。
 */
@Serializable
data class AlarmReportState(
    /** `MotionDetection`，0/1。 */
    val motionDetection: Int = 1,
    /** `FieldDetection`，0/1。 */
    val fieldDetection: Int = 0,
)

/**
 * `AlarmReport` 的取值表、出厂默认与线格式。
 *
 * ⭐ **出厂默认不是随手挑的，而是照设备真实行为填的**：
 * 本模拟器确实会上报移动侦测报警（见 `AlarmTemplates` 里 `AlarmMethod.Video` 的
 * 「移动侦测触发」与 `SimulatorEngine` 的报警命令路径），但它**没有**区域入侵检测。
 * 所以默认 = `MotionDetection=1` / `FieldDetection=0`。
 *
 * ⛔ 反过来写（两个都回 0）就是**设备替平台编答案**：平台面板会显示"报警上报已关闭"，
 * 而设备此刻正在上报报警 —— 现场看到的就是"开关关了但报警还在来"，属于最难解释的一类假状态。
 * 通则：**凡"设备当前生效值"的出厂默认，必须从设备实际行为派生，不能为了图省事填 0。**
 *
 * 取值只接受 0/1（XSD 注释明写「取值0-关闭，1-打开」）。越界一律拒绝落库 ——
 * 理由同 [FrameMirrorConfig]：落库一个 3 之后，回读会永久吐非法值。
 */
object AlarmReportConfig {

    const val OFF = 0
    const val ON = 1

    fun isValidSwitch(value: Int): Boolean = value == OFF || value == ON

    /** 出厂默认：见类注释 —— 照设备真实上报行为填（移动侦测开、区域入侵关）。 */
    val DEFAULT = AlarmReportState(motionDetection = ON, fieldDetection = OFF)

    /** 解析下发报文。两个字段都是**必选**，缺任一个即整块 [ConfigParse.Rejected]。 */
    fun parse(xml: String): ConfigParse<AlarmReportState> {
        val body = configBlockBody(xml, DeviceConfigBlock.AlarmReport.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.AlarmReport.configType)
        val motion = childInt(body, "MotionDetection")
            ?: return ConfigParse.Rejected("缺必选字段 MotionDetection")
        val field = childInt(body, "FieldDetection")
            ?: return ConfigParse.Rejected("缺必选字段 FieldDetection")
        if (!isValidSwitch(motion)) {
            return ConfigParse.Rejected("MotionDetection=$motion 非法（只能 $OFF/$ON）")
        }
        if (!isValidSwitch(field)) {
            return ConfigParse.Rejected("FieldDetection=$field 非法（只能 $OFF/$ON）")
        }
        return ConfigParse.Accepted(AlarmReportState(motionDetection = motion, fieldDetection = field))
    }

    /**
     * 回读应答块。两个元素**都必须输出**（标准里是必选），不因为值是 0 就省掉 ——
     * 省掉之后平台侧 `xml:"MotionDetection"` 解出零值，与"元素真的不在"分不开。
     */
    fun render(state: AlarmReportState): String = buildString {
        append("<AlarmReport>\n")
        append("<MotionDetection>").append(state.motionDetection).append("</MotionDetection>\n")
        append("<FieldDetection>").append(state.fieldDetection).append("</FieldDetection>\n")
        append("</AlarmReport>\n")
    }
}
