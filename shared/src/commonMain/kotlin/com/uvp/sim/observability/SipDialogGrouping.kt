package com.uvp.sim.observability

import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse

/**
 * sngrep 风格时序图分组算法 — 纯函数,可在 commonTest 单测。
 *
 * 输入:按时间序的 [SipFlowEvent] 列表 + 媒体段事件列表。
 * 输出:按 Dialog/HeartbeatCluster 聚合的 [FlowItem] 列表(时间倒序,最新在前)。
 *
 * 算法步骤:
 * 1. 拆出心跳消息(CmdType=Keepalive 的 MESSAGE 请求 + 它对应的 200 响应)
 * 2. 把心跳按"连续邻接 + < 5min 间隔"折叠成 HeartbeatCluster
 * 3. 剩余消息按 Call-ID 分组 → Dialog
 * 4. 把 INVITE Call-ID 对应的 MediaSegment 挂到 Dialog 尾
 * 5. 输出按 Dialog/Cluster 起始时间倒序
 */
object SipDialogGrouping {

    /** 心跳簇切割阈值:间隔超过此值不再合并(spec §11.2)。 */
    const val HEARTBEAT_GAP_THRESHOLD_MS: Long = 5L * 60 * 1000

    /** 最小心跳数 — 单条心跳不折叠(否则用户感觉像 bug)。 */
    const val HEARTBEAT_MIN_COUNT: Int = 2

    fun group(
        events: List<SipFlowEvent>,
        mediaSegments: List<MediaSegmentEvent> = emptyList()
    ): List<FlowItem> {
        if (events.isEmpty()) return emptyList()

        // 标记每条事件:是否心跳。请求里看 body XML CmdType,响应里看 CSeq method。
        // 简化:只看请求 body 是否含 Keepalive token;响应跟随其请求的 callId 走分组,
        // 但若整个 callId 全是心跳/心跳响应,作为心跳簇候选。
        val grouped = events.groupBy { it.callId }

        val dialogs = mutableListOf<FlowItem.Dialog>()
        val heartbeatBuckets = mutableListOf<HeartbeatBucket>()

        for ((callId, evs) in grouped) {
            val sorted = evs.sortedBy { it.timestampMs }
            if (isHeartbeatOnly(sorted)) {
                // 每个心跳 callId 进 bucket — 后续按时间合并相邻 callId
                val firstReq = sorted.firstOrNull { it.message is SipRequest } ?: continue
                heartbeatBuckets += HeartbeatBucket(
                    timestampMs = firstReq.timestampMs,
                    rows = sorted.map { it.toMessageRow() }
                )
            } else {
                val rows = sorted.map { it.toMessageRow() as DialogRow }.toMutableList()
                // 把 MediaSegment 挂到对应 INVITE Dialog 尾
                mediaSegments.filter { it.callId == callId }.forEach { ms ->
                    rows.add(
                        DialogRow.MediaSegment(
                            startedAtMs = ms.startedAtMs,
                            stoppedAtMs = ms.stoppedAtMs,
                            frameCount = ms.frameCount,
                            packetCount = ms.packetCount,
                            callId = ms.callId,
                            remoteHost = ms.remoteHost,
                            remotePort = ms.remotePort
                        )
                    )
                }
                dialogs += FlowItem.Dialog(
                    callId = callId,
                    startedAtMs = sorted.first().timestampMs,
                    rows = rows
                )
            }
        }

        val clusters = mergeHeartbeatBuckets(heartbeatBuckets)

        // 合并 + 倒序(最新在前)
        return (dialogs + clusters).sortedByDescending(::flowItemSortKey)
    }

    private fun flowItemSortKey(item: FlowItem): Long = when (item) {
        is FlowItem.Dialog -> item.startedAtMs
        is FlowItem.HeartbeatCluster -> item.firstAtMs
    }

    private fun isHeartbeatOnly(events: List<SipFlowEvent>): Boolean {
        val req = events.firstOrNull { it.message is SipRequest }?.message as? SipRequest
            ?: return false
        if (req.method != SipMethod.MESSAGE) return false
        return req.body.decodeToString().contains("<CmdType>Keepalive</CmdType>")
    }

    private fun SipFlowEvent.toMessageRow(): DialogRow.Message {
        val (title, summary) = when (val m = message) {
            is SipRequest -> m.method.name to m.requestUri
            is SipResponse -> "${m.statusCode} ${m.reasonPhrase}" to m.cseqMethodOrEmpty()
        }
        return DialogRow.Message(
            timestampMs = timestampMs,
            outgoing = outgoing,
            title = title,
            summary = summary,
            rawMessage = message
        )
    }

    private fun SipResponse.cseqMethodOrEmpty(): String =
        cseqRaw()?.split(" ")?.getOrNull(1) ?: ""

    private data class HeartbeatBucket(val timestampMs: Long, val rows: List<DialogRow.Message>)

    /**
     * 把多个独立心跳 callId(每个 bucket 仅 1-2 条)按时间相邻合成大簇。
     *
     * 相邻定义:bucket A 最后一行时间 + HEARTBEAT_GAP_THRESHOLD_MS >= bucket B 第一行时间。
     */
    private fun mergeHeartbeatBuckets(buckets: List<HeartbeatBucket>): List<FlowItem.HeartbeatCluster> {
        if (buckets.isEmpty()) return emptyList()
        val sorted = buckets.sortedBy { it.timestampMs }
        val merged = mutableListOf<MutableList<DialogRow.Message>>()
        merged += mutableListOf<DialogRow.Message>().apply { addAll(sorted[0].rows) }

        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            val tail = merged.last()
            val tailEnd = tail.maxOf { it.timestampMs }
            if (cur.timestampMs - tailEnd <= HEARTBEAT_GAP_THRESHOLD_MS) {
                tail.addAll(cur.rows)
            } else {
                merged += mutableListOf<DialogRow.Message>().apply { addAll(cur.rows) }
            }
        }

        return merged.mapNotNull { rows ->
            // 单条心跳不折叠 — 显示太突兀
            val reqCount = rows.count { it.rawMessage is SipRequest }
            if (reqCount < HEARTBEAT_MIN_COUNT) return@mapNotNull null
            FlowItem.HeartbeatCluster(
                firstAtMs = rows.minOf { it.timestampMs },
                lastAtMs = rows.maxOf { it.timestampMs },
                count = reqCount,
                rows = rows.sortedBy { it.timestampMs }
            )
        }
    }
}
