package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlinx.serialization.Serializable

/**
 * GB/T 28181-2022 **A.2.1.16 报警录像配置类型**（`videoAlarmRecordCfgType`）的当前取值。
 *
 * 字段与必选性（逐个抄自原文）：
 *   - [recordEnable]  `RecordEnable`  是否启用报警录像配置，0-否 / 1-是（**必选**）
 *   - [recordTime]    `RecordTime`    录像延时时间，报警时间点**后**的时间，单位秒（可选）
 *   - [preRecordTime] `PreRecordTime` 预录时间，报警时间点**前**的时间，单位秒（可选）
 *   - [streamNumber]  `StreamNumber`  码流编号，0-主码流 / 1-子码流1 / 2-子码流2…（**必选**）
 *
 * ⛔ 两个可选字段用 `Int?`：`null` = **报文里没有这个元素**，与"平台写了 0 秒"是两件事。
 * 回读对账时这两者必须能分开（同 `VideoParamState.videoBitRate` 的口径）——
 * 合并之后平台会看到"没配延时"被显示成"延时 0 秒"，而 0 秒在业务上是"报警即停录"，
 * 是完全不同的行为。
 *
 * ⚠️ 本仓前端这个组多了个标准里**不存在**的 `triggerEvent` 字段、又**缺** `StreamNumber` ——
 * 那是照海康 ISP 口径编的。前端接后端之前必须先按本条对齐。
 */
@Serializable
data class VideoAlarmRecordState(
    /** `RecordEnable`，0/1。默认 0 = 不启用报警录像。 */
    val recordEnable: Int = 0,
    /** `RecordTime`（秒）。null = 平台未下发该项（**不是 0**）。 */
    val recordTime: Int? = null,
    /** `PreRecordTime`（秒）。null = 平台未下发该项（**不是 0**）。 */
    val preRecordTime: Int? = null,
    /** `StreamNumber`：0-主码流 / 1-子码流1…。默认主码流。 */
    val streamNumber: Int = 0,
)

/**
 * `VideoAlarmRecord` 的取值表、出厂默认与线格式。
 *
 * 出厂默认 = 不启用报警录像 + 主码流 + 两个可选字段**缺席**（`null`）。
 * 「默认不启用」是设备上电后的物理事实（真机不会自己开始录报警）；而两个时间字段
 * 报 `null` 而不是 `0`，是因为设备**从来没有被配过**这两个值 —— 报 0 就是编答案。
 */
object VideoAlarmRecordConfig {

    private const val OFF = 0
    private const val ON = 1
    private const val MAX_SECONDS = 86_400

    /** 出厂默认：不启用，主码流，两个延时字段缺席。 */
    fun defaultFor(config: SimConfig): VideoAlarmRecordState = VideoAlarmRecordState(
        recordEnable = OFF,
        recordTime = null,
        preRecordTime = null,
        // ⭐ 与目录里声明的码流清单同源：默认走**主码流**，但取值必须落在设备真的有的那几路里。
        //   这里取清单最小值（= 0），不硬编码 —— 免得将来清单改成从 1 起时这里悄悄越界。
        streamNumber = VideoParamAttribute.streamNumbersOf(config.device.channel.streamNumberList).first(),
    )

    /**
     * 解析下发报文。
     *
     * 两个可选字段：**元素缺席 → null**；在场但非整数 → **Rejected**（不猜成 0）。
     * 两个必选字段（`RecordEnable` / `StreamNumber`）缺席即整块 Rejected。
     */
    fun parse(xml: String): ConfigParse<VideoAlarmRecordState> {
        val body = configBlockBody(xml, DeviceConfigBlock.VideoAlarmRecord.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.VideoAlarmRecord.configType)

        val enable = childInt(body, "RecordEnable")
            ?: return ConfigParse.Rejected("缺必选字段 RecordEnable")
        if (enable != OFF && enable != ON) {
            return ConfigParse.Rejected("RecordEnable=$enable 非法（只能 $OFF/$ON）")
        }

        val streamNumber = childInt(body, "StreamNumber")
            ?: return ConfigParse.Rejected("缺必选字段 StreamNumber")
        if (streamNumber < 0) {
            return ConfigParse.Rejected("StreamNumber=$streamNumber 非法（0 起，主码流=0）")
        }

        val recordTime = optionalSeconds(body, "RecordTime")
            ?: return ConfigParse.Rejected("RecordTime 非整数（在场但不合法）")
        val preRecordTime = optionalSeconds(body, "PreRecordTime")
            ?: return ConfigParse.Rejected("PreRecordTime 非整数（在场但不合法）")

        return ConfigParse.Accepted(
            VideoAlarmRecordState(
                recordEnable = enable,
                recordTime = recordTime.value,
                preRecordTime = preRecordTime.value,
                streamNumber = streamNumber,
            )
        )
    }

    /**
     * 可选秒数字段的解析。用一个**包装类**把「缺席」与「值 = 0」分开 ——
     * 直接返回 `Int?` 会让"元素缺席"和"元素非法"都变成 null，调用方就再也分不清
     * 该不该记 warn 了（这正是 [ConfigParse] 三态要解决的问题，只是这里下沉到字段级）。
     */
    private class OptionalValue(val value: Int?)

    private fun optionalSeconds(body: String, tag: String): OptionalValue? {
        val raw = ManscdpParser.tagValue(body, tag)?.trim() ?: return OptionalValue(null)
        if (raw.isEmpty()) return OptionalValue(null)
        val v = raw.toIntOrNull() ?: return null
        if (v < 0 || v > MAX_SECONDS) return null
        return OptionalValue(v)
    }

    /**
     * 回读应答块。
     *
     * ⛔ 两个可选字段**缺席时整个元素不出现** —— 不能输出空元素
     * `<RecordTime></RecordTime>`（平台会解成空串，与"没有这个元素"含义不同）。
     */
    fun render(state: VideoAlarmRecordState): String = buildString {
        append("<VideoAlarmRecord>\n")
        append("<RecordEnable>").append(state.recordEnable).append("</RecordEnable>\n")
        state.recordTime?.let { append("<RecordTime>").append(it).append("</RecordTime>\n") }
        state.preRecordTime?.let { append("<PreRecordTime>").append(it).append("</PreRecordTime>\n") }
        append("<StreamNumber>").append(state.streamNumber).append("</StreamNumber>\n")
        append("</VideoAlarmRecord>\n")
    }
}
