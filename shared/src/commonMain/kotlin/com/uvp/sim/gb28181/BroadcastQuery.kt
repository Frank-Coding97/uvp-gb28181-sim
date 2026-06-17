package com.uvp.sim.gb28181

/**
 * GB/T 28181 §9.8 语音广播 `MESSAGE` body 的解析结果封装。
 *
 * 平台发起广播的 Notify body 形如:
 * ```
 * <Notify>
 *   <CmdType>Broadcast</CmdType>
 *   <SN>1</SN>
 *   <SourceID>34020000002000000001</SourceID>   ← 平台(广播音频源)
 *   <TargetID>34020000001320000001</TargetID>   ← 设备(被喊话方,须 = 本机 deviceId)
 * </Notify>
 * ```
 */
data class BroadcastQuery(
    val sn: String?,
    val sourceId: String?,
    val targetId: String?
) {
    companion object {
        fun parse(xml: String): BroadcastQuery = BroadcastQuery(
            sn = ManscdpParser.sn(xml),
            sourceId = ManscdpParser.sourceId(xml),
            targetId = ManscdpParser.targetId(xml)
        )
    }
}
