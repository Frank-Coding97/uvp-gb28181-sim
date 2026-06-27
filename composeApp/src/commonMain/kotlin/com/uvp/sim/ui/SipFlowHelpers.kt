package com.uvp.sim.ui

import com.uvp.sim.observability.Gb28181IdParser
import com.uvp.sim.ui.model.DialogRowDto
import com.uvp.sim.ui.model.FlowItemDto
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SipFlowView 用的纯函数:业务名推断、时间格式化、Dialog→纯文本序列化。
 *
 * 拆出原因:SipFlowView.kt 主文件 > 400 行,这些 helper 没有 @Composable 依赖,
 * 留在主文件干扰阅读,挪出来后主文件聚焦视图渲染。
 */

internal fun List<DialogRowDto>.lastTimestamp(): Long? = mapNotNull {
    when (it) {
        is DialogRowDto.Message -> it.timestampMs
        is DialogRowDto.MediaSegment -> it.stoppedAtMs ?: it.startedAtMs
    }
}.maxOrNull()

/**
 * 把 hex Call-ID 翻译成业务名 — 运维一眼能看懂。
 *
 * 启发式:看 Dialog 第一条消息的 SIP method,推断业务类型。
 * 备用:hex 短 hash 后缀作 trace token(老板要 grep 时用)。
 */
internal fun dialogBusinessTitle(dialog: FlowItemDto.Dialog): String {
    val first = dialog.rows.firstOrNull() as? DialogRowDto.Message
    val token = dialog.callId.substringBefore('@').take(6)

    val biz = when (val msg = first?.rawMessage) {
        is SipMessageDto.Request -> when (msg.method) {
            SipMethodDto.REGISTER -> "📡 注册"
            SipMethodDto.INVITE -> {
                val parsed = Gb28181IdParser.parseFromRequestUri(msg.requestUri)
                if (parsed != null) "🎬 视频点播 · ${parsed.label}"
                else "🎬 视频点播"
            }
            SipMethodDto.MESSAGE -> {
                val body = msg.body
                when {
                    "<CmdType>Catalog</CmdType>" in body -> "📂 目录查询"
                    "<CmdType>DeviceInfo</CmdType>" in body -> "📋 设备信息"
                    "<CmdType>Alarm</CmdType>" in body -> "🚨 告警上报"
                    else -> "💬 MESSAGE"
                }
            }
            SipMethodDto.BYE -> "✋ 结束通话"
            else -> msg.method.name
        }
        is SipMessageDto.Response -> "↩ ${msg.statusCode}"
        else -> "Dialog"
    }
    return "$biz  · #$token"
}

internal fun formatHmsFlow(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(ldt.hour, ldt.minute, ldt.second)
}

internal fun formatHmsMillis(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--.---"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val ms = epochMs % 1000
    return "%02d:%02d:%02d.%03d".format(ldt.hour, ldt.minute, ldt.second, ms)
}

/**
 * 把整段 Dialog 序列化成可粘贴的纯文本(带分隔)——给"复制本次会话"按钮用。
 *
 * 每条消息原样输出 SIP wire 格式(请求行/状态行 + headers + body),
 * 媒体段以 `--- RTP ... ---` 注释形式标注,方便贴到工单或 IDE 里直接看。
 */
internal fun formatDialogForCopy(dialog: FlowItemDto.Dialog, title: String): String = buildString {
    appendLine("# $title")
    appendLine("# Call-ID: ${dialog.callId}")
    appendLine("# 开始: ${formatHmsMillis(dialog.startedAtMs)}  · 共 ${dialog.rows.size} 条")
    appendLine()
    dialog.rows.forEachIndexed { idx, row ->
        when (row) {
            is DialogRowDto.Message -> {
                val arrow = if (row.outgoing) "→ sim → 平台" else "← 平台 → sim"
                appendLine("--- [${idx + 1}] ${formatHmsMillis(row.timestampMs)} $arrow ${row.title} ---")
                append(formatSipMessage(row.rawMessage))
                appendLine()
            }
            is DialogRowDto.MediaSegment -> {
                val end = row.stoppedAtMs?.let { formatHmsMillis(it) } ?: "(推送中)"
                appendLine("--- [${idx + 1}] RTP ${formatHmsMillis(row.startedAtMs)} → $end ---")
                appendLine("# 目标: ${row.remoteHost}:${row.remotePort}")
                appendLine("# 帧数: ${row.frameCount}  包数: ${row.packetCount}")
                appendLine()
            }
        }
    }
}

private fun formatSipMessage(msg: SipMessageDto): String = buildString {
    when (msg) {
        is SipMessageDto.Request -> appendLine("${msg.method.name} ${msg.requestUri} ${msg.sipVersion}")
        is SipMessageDto.Response -> appendLine("${msg.sipVersion} ${msg.statusCode} ${msg.reasonPhrase}")
    }
    msg.headers.forEach { appendLine("${it.name}: ${it.value}") }
    if (msg.body.isNotEmpty()) {
        appendLine()
        append(msg.body)
        if (!endsWith('\n')) appendLine()
    }
}
