package com.uvp.sim.ui

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.observability.DialogRow
import com.uvp.sim.observability.FlowItem
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 日志导出 — 纯字符串拼接,平台壳负责调 [shareText] 推系统分享面板。
 *
 * 三种格式:
 * - SIP 列表(filter / chip / events)
 * - SIP 时序图(ASCII Dialog + 心跳簇 + RTP 占位)
 * - 系统日志(level / tag 过滤)
 */
object LogExport {

    fun formatSipList(
        events: List<SimEvent>,
        sessionMarker: SessionMarker?,
        chipFilter: String,
        nowMs: Long
    ): String = buildString {
        appendLine("=== UVP GB28181 Sim — SIP 日志(列表视图)")
        sessionMarker?.let {
            appendLine("=== 会话 #${it.sessionId} · 起于 ${formatYmd(it.startedAtMs)}")
        }
        appendLine("=== 导出时间 ${formatYmd(nowMs)}")
        appendLine("=== 过滤器: chip=$chipFilter")
        appendLine("=== 共 ${events.size} 条")
        appendLine()
        events.forEach { ev ->
            appendLine(formatSipEventLine(ev))
        }
    }

    fun formatSipFlow(
        items: List<FlowItem>,
        sessionMarker: SessionMarker?,
        nowMs: Long
    ): String = buildString {
        appendLine("=== UVP GB28181 Sim — SIP 时序图")
        sessionMarker?.let {
            appendLine("=== 会话 #${it.sessionId} · 起于 ${formatYmd(it.startedAtMs)}")
        }
        appendLine("=== 导出时间 ${formatYmd(nowMs)}")
        val dialogCount = items.count { it is FlowItem.Dialog }
        val clusterCount = items.count { it is FlowItem.HeartbeatCluster }
        appendLine("=== $dialogCount 个 Dialog · $clusterCount 个心跳簇")
        appendLine()
        items.forEach { item ->
            when (item) {
                is FlowItem.Dialog -> appendDialog(item)
                is FlowItem.HeartbeatCluster -> appendCluster(item)
            }
            appendLine()
        }
    }

    fun formatSystemLogs(
        logs: List<SystemLog>,
        sessionMarker: SessionMarker?,
        levelThreshold: LogLevel,
        tagFilter: LogTag?,
        nowMs: Long
    ): String = buildString {
        appendLine("=== UVP GB28181 Sim — 系统日志")
        sessionMarker?.let {
            appendLine("=== 会话 #${it.sessionId} · 起于 ${formatYmd(it.startedAtMs)}")
        }
        appendLine("=== 导出时间 ${formatYmd(nowMs)}")
        val tagText = tagFilter?.display ?: "全部"
        appendLine("=== 过滤器: tag=$tagText, level≥${levelThreshold.name}")
        appendLine("=== 共 ${logs.size} 条")
        appendLine()
        logs.forEach { log ->
            appendLine("[${formatHmsMs(log.timestampMs)}] [${log.level.short}] [${log.tag.display}] ${log.message}")
            log.detail?.let { d ->
                d.lines().forEach { appendLine("    $it") }
            }
        }
    }

    private fun StringBuilder.appendDialog(d: FlowItem.Dialog) {
        appendLine("----- Dialog: ${d.callId} -----")
        d.rows.forEach { row ->
            when (row) {
                is DialogRow.Message -> {
                    val arrow = if (row.outgoing) "sim → 平台" else "sim ← 平台"
                    val title = "${row.title}".padEnd(20)
                    appendLine("[${formatHmsMs(row.timestampMs)}] $arrow  $title  ${row.summary}")
                }
                is DialogRow.MediaSegment -> {
                    val tail = if (row.stoppedAtMs == null)
                        "RTP 推送中: ${row.frameCount} 帧 / ${row.packetCount} 包"
                    else
                        "RTP 已停: ${row.frameCount} 帧 / ${row.packetCount} 包"
                    appendLine("[${formatHmsMs(row.startedAtMs)}] ┄┄ ($tail) → ${row.remoteHost}:${row.remotePort}")
                }
            }
        }
    }

    private fun StringBuilder.appendCluster(c: FlowItem.HeartbeatCluster) {
        appendLine("----- Heartbeat Cluster (${c.count} 条心跳 / ${formatHmsMs(c.firstAtMs)} - ${formatHmsMs(c.lastAtMs)}) -----")
        c.rows.forEach { row ->
            val arrow = if (row.outgoing) "sim → 平台" else "sim ← 平台"
            appendLine("[${formatHmsMs(row.timestampMs)}] $arrow  ${row.title}")
        }
    }

    private fun formatSipEventLine(ev: SimEvent): String = when (ev) {
        is SimEvent.RegistrationStarted -> "→ REGISTER  sip:${ev.server}"
        is SimEvent.RegistrationChallenged -> "← 401      认证挑战"
        is SimEvent.RegistrationSucceeded -> "← 200      OK · expires=${ev.expiresSeconds}s"
        is SimEvent.RegistrationFailed -> "← FAIL     ${ev.reason}"
        is SimEvent.HeartbeatSent -> "→ MSG      Keepalive CSeq ${ev.sequence}"
        is SimEvent.HeartbeatAcknowledged -> "← 200      心跳确认 #${ev.sequence}"
        is SimEvent.IncomingInvite -> "← INVITE   ${ev.callId}"
        is SimEvent.StreamStarted -> "→ 200      OK · RTP → ${ev.remoteHost}:${ev.remotePort} ssrc=${ev.ssrc}"
        is SimEvent.StreamStopped -> "■ STOP     ${ev.frameCount}f / ${ev.packetCount}p · ${ev.reason}"
        is SimEvent.StreamStats -> "· STATS    ${ev.frameCount}f / ${ev.packetCount}p"
        is SimEvent.CallEnded -> "← BYE      ${ev.reason}"
        is SimEvent.SnapshotReported -> "→ ALARM    抓拍 SN=${ev.sn}"
        is SimEvent.MessageSent -> "→ ${msgShort(ev.message).padEnd(8)} ${msgLine(ev.message)}"
        is SimEvent.MessageReceived -> "← ${msgShort(ev.message).padEnd(8)} ${msgLine(ev.message)}"
        is SimEvent.TransportError -> "⚠ ERROR    ${ev.description}"
        is SimEvent.SubscribeReceived -> "← SUBSCRIBE ${ev.kind} from=${ev.subscriber}"
        is SimEvent.NotifySent -> "→ NOTIFY   ${ev.kind} SN=${ev.sn}"
        is SimEvent.SubscribeExpired -> "· EXPIRED  ${ev.kind} ${ev.subscriber}"
        is SimEvent.SubscribeRefreshed -> "← REFRESH  expires=${ev.newExpiresSeconds}s"
        is SimEvent.HeartbeatTimeoutDetected -> "⚠ HB-LOST  心跳连续 ${ev.missedCount}/${ev.maxAllowed} 未响应"
        is SimEvent.AutoReregisterTriggered -> "↻ RE-REG   自动重注册 · ${ev.reason}"
        is SimEvent.RegistrationRetryScheduled -> "↻ RETRY    第 ${ev.attempt} 次重试 · ${ev.delayMs}ms 后"
        is SimEvent.InviteAckTimeout -> "⚠ ACK-TO   平台 ACK 未到达 · ${ev.callId}"
        is SimEvent.DeviceControlReceived -> "← CTRL     ${ev.commandType} · ${ev.detail}"
    }

    private fun msgShort(m: SipMessage): String = when (m) {
        is SipRequest -> m.method.name.take(8)
        is SipResponse -> m.statusCode.toString()
    }

    private fun msgLine(m: SipMessage): String = when (m) {
        is SipRequest -> "${m.method.name} ${m.requestUri}"
        is SipResponse -> "${m.statusCode} ${m.reasonPhrase}"
    }

    fun filename(kind: String, nowMs: Long): String {
        val ldt = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.currentSystemDefault())
        val ts = "%04d%02d%02d-%02d%02d%02d".format(
            ldt.year, ldt.monthNumber, ldt.dayOfMonth,
            ldt.hour, ldt.minute, ldt.second
        )
        return "uvp-sim-log-$kind-$ts.txt"
    }
}

private fun formatHmsMs(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--.---"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val ms = epochMs % 1000
    return "%02d:%02d:%02d.%03d".format(ldt.hour, ldt.minute, ldt.second, ms)
}

private fun formatYmd(epochMs: Long): String {
    if (epochMs <= 0) return "----"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%04d-%02d-%02d %02d:%02d:%02d".format(
        ldt.year, ldt.monthNumber, ldt.dayOfMonth,
        ldt.hour, ldt.minute, ldt.second
    )
}

/**
 * 平台分享 — Android: ACTION_SEND text/plain;iOS: stub(P0 不实现)。
 */
expect fun shareText(filename: String, content: String)
