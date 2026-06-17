package com.uvp.sim.gb28181

/**
 * Minimal MANSCDP+xml parsing for the GB28181 commands the simulator must
 * understand: Catalog query, plus future Alarm-ack / DeviceInfo / DeviceStatus.
 *
 * We deliberately avoid bringing in a full XML library (xmlutil etc.) for now
 * because GB28181 message bodies are tiny, well-formed, and predictable. A
 * tag-fishing approach is sufficient and keeps the dependency footprint small.
 */
object ManscdpParser {

    /**
     * Extract the value of a top-level element by tag name.
     * Returns null if not found. Naive but deterministic for GB28181 bodies.
     */
    fun tagValue(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, startIndex = start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim()
    }

    /** Parse a Query / Notify / Response wrapper to identify the command type. */
    fun cmdType(xml: String): String? = tagValue(xml, "CmdType")

    /** SN is monotonically increasing per CmdType, used to correlate response to query. */
    fun sn(xml: String): String? = tagValue(xml, "SN")

    fun deviceId(xml: String): String? = tagValue(xml, "DeviceID")

    /** GB/T 28181 §9.8 语音广播 Notify 的广播源 ID(平台侧)。 */
    fun sourceId(xml: String): String? = tagValue(xml, "SourceID")

    /** GB/T 28181 §9.8 语音广播 Notify 的目标 ID(设备侧,须匹配本机 deviceId)。 */
    fun targetId(xml: String): String? = tagValue(xml, "TargetID")

    /**
     * GB/T 28181 §9.3 DeviceControl <RecordCmd> 值。标准取值 "Record" / "StopRecord"。
     * 实现侧不做大小写归一,如实返回(交由 [com.uvp.sim.domain.SimulatorEngine] 判断)。
     */
    fun recordCmd(xml: String): String? = tagValue(xml, "RecordCmd")
}
