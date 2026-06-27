package com.uvp.sim.ui

import androidx.compose.ui.graphics.Color
import com.uvp.sim.ui.model.ResetSourceDto
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto

/**
 * SipLogListView 用的行规格 — `SimEventDto → LogRowSpec` 的映射 + 配套小辅助。
 *
 * 拆出原因:SipLogListView.kt 主文件 > 400 行,其中近 200 行就是这个 when 表,
 * 移出来让主文件聚焦渲染。这里全是纯函数,无 @Composable 依赖,易做单测。
 */

internal data class LogRowSpec(
    val time: String,
    val arrow: String,
    val outgoing: Boolean,
    val method: String,
    val badgeColor: Color,
    val content: String,
    val highlight: Boolean = false,
    val category: String = ""
)

internal fun logRowSpec(ev: SimEventDto): LogRowSpec? = when (ev) {
    is SimEventDto.RegistrationStarted -> LogRowSpec(
        "", "→", true, "REG", UvpColor.Primary, "sip:${ev.server}", category = "REGISTER"
    )
    is SimEventDto.RegistrationChallenged -> LogRowSpec(
        "", "←", false, "401", UvpColor.Warning, "认证挑战", category = "REGISTER"
    )
    is SimEventDto.RegistrationSucceeded -> LogRowSpec(
        "", "←", false, "200", UvpColor.Success, "OK · 注册成功 expires=${ev.expiresSeconds}s", category = "REGISTER"
    )
    is SimEventDto.RegistrationFailed -> LogRowSpec(
        "", "←", false, "ERR", UvpColor.Danger, ev.reason, category = "REGISTER"
    )
    is SimEventDto.HeartbeatSent -> LogRowSpec(
        "", "→", true, "MSG", UvpColor.Info, "Keepalive · CSeq ${ev.sequence}", category = "MESSAGE"
    )
    is SimEventDto.HeartbeatAcknowledged -> LogRowSpec(
        "", "←", false, "200", UvpColor.Success, "OK · 心跳确认", category = "MESSAGE"
    )
    is SimEventDto.IncomingInvite -> LogRowSpec(
        "", "←", false, "INV", UvpColor.Warning, "SDP m=video · ${ev.callId.take(20)}",
        highlight = true, category = "INVITE"
    )
    is SimEventDto.StreamStarted -> LogRowSpec(
        "", "→", true, "200", UvpColor.Success, "OK · SDP answer → RTP ${ev.remoteHost}:${ev.remotePort}",
        category = "INVITE"
    )
    is SimEventDto.StreamStopped -> LogRowSpec(
        "", "■", true, "END", UvpColor.TextHint, "${ev.frameCount}f / ${ev.packetCount}p · ${ev.reason}",
        category = "BYE"
    )
    is SimEventDto.StreamStats -> null  // 时序图 MediaSegment 显示,列表不重复
    is SimEventDto.CallEnded -> LogRowSpec(
        "", "←", false, "BYE", UvpColor.Danger, ev.reason, category = "BYE"
    )
    is SimEventDto.SnapshotReported -> LogRowSpec(
        "", "→", true, "ALM", UvpColor.Warning, "抓拍 SN=${ev.sn}", category = "MESSAGE"
    )
    is SimEventDto.SnapshotUploaded -> LogRowSpec(
        "", "→", true, "SNP", UvpColor.Success,
        "抓拍上传 ${ev.count}/${ev.total} · ${ev.snapShotId}",
        category = "MESSAGE"
    )
    is SimEventDto.SnapshotUploadFailed -> LogRowSpec(
        "", "⚠", true, "SNP", UvpColor.Danger,
        "抓拍上传失败 · ${ev.snapShotId}",
        highlight = true,
        category = "MESSAGE"
    )
    is SimEventDto.MediaStatusSent -> LogRowSpec(
        "", "→", true, "STA", UvpColor.Info,
        "MediaStatus ${ev.notifyType} · ${ev.subscriberCount} 订阅",
        category = "MESSAGE"
    )
    is SimEventDto.MessageSent -> LogRowSpec(
        "", "→", true, msgMethodShort(ev.message), UvpColor.Primary,
        msgContent(ev.message), category = msgCategory(ev.message)
    )
    is SimEventDto.MessageReceived -> LogRowSpec(
        "", "←", false, msgMethodShort(ev.message), UvpColor.Success,
        msgContent(ev.message), category = msgCategory(ev.message)
    )
    is SimEventDto.TransportError -> LogRowSpec(
        "", "⚠", true, "ERR", UvpColor.Danger, ev.description, category = ""
    )
    is SimEventDto.DeviceControlReceived -> LogRowSpec(
        "", "←", false, "CTL", UvpColor.Info,
        "${ev.commandType} · ${ev.detail.take(40)}",
        category = "CONTROL"
    )
    is SimEventDto.SubscribeReceived -> LogRowSpec(
        "", "←", false, "SUB", UvpColor.Info, "${ev.kind} · from=${ev.subscriber}", category = "SUBSCRIBE"
    )
    is SimEventDto.NotifySent -> LogRowSpec(
        "", "→", true, "NTF", UvpColor.Primary, "${ev.kind} · SN=${ev.sn}", category = "NOTIFY"
    )
    is SimEventDto.SubscribeExpired -> LogRowSpec(
        "", "·", true, "EXP", UvpColor.TextHint, "${ev.kind} 过期", category = "SUBSCRIBE"
    )
    is SimEventDto.SubscribeRefreshed -> LogRowSpec(
        "", "←", false, "REF", UvpColor.Success, "续订 expires=${ev.newExpiresSeconds}s", category = "SUBSCRIBE"
    )
    is SimEventDto.HeartbeatTimeoutDetected -> LogRowSpec(
        "", "⚠", true, "HB!", UvpColor.Danger,
        "心跳连续 ${ev.missedCount}/${ev.maxAllowed} 未响应",
        highlight = true, category = "MESSAGE"
    )
    is SimEventDto.AutoReregisterTriggered -> LogRowSpec(
        "", "↻", true, "RE", UvpColor.Warning, "自动重注册 · ${ev.reason}",
        highlight = true, category = "REGISTER"
    )
    is SimEventDto.RegistrationRetryScheduled -> LogRowSpec(
        "", "↻", true, "TRY", UvpColor.Info,
        "第 ${ev.attempt} 次重试 · ${ev.delayMs}ms 后", category = "REGISTER"
    )
    is SimEventDto.InviteAckTimeout -> LogRowSpec(
        "", "⚠", true, "ACK", UvpColor.Warning,
        "平台 ACK 未到达 · ${ev.callId.take(20)}",
        highlight = true, category = "INVITE"
    )
    is SimEventDto.DeviceControlReceived -> LogRowSpec(
        "", "←", false, "CTRL", UvpColor.Info,
        "设备控制 · ${ev.commandType} · ${ev.detail.take(30)}",
        category = "CONTROL"
    )
    is SimEventDto.AlarmFired -> LogRowSpec(
        "", "→", true, "ALM", UvpColor.Warning,
        "报警 · ${ev.type.label}/${ev.priority.label} · ${ev.description.take(30)}",
        highlight = true, category = "ALARM"
    )
    is SimEventDto.AlarmReset -> LogRowSpec(
        "", "·", true, "RST", UvpColor.Info,
        "报警复位 · ${alarmResetBy(ev.by)}", category = "ALARM"
    )
    is SimEventDto.AlarmSubscribed -> LogRowSpec(
        "", "←", false, "SUB", UvpColor.Info,
        "报警订阅 · from=${ev.subscriber} expires=${ev.expires}s", category = "SUBSCRIBE"
    )
    is SimEventDto.AlarmNotifySent -> LogRowSpec(
        "", "→", true, "NTF", UvpColor.Primary,
        "报警 NOTIFY · SN=${ev.sn} → ${ev.subscriber}", category = "NOTIFY"
    )
    is SimEventDto.AlarmSubscriptionExpired -> LogRowSpec(
        "", "·", true, "EXP", UvpColor.TextHint,
        "报警订阅过期 · ${ev.subscriber}", category = "SUBSCRIBE"
    )
    is SimEventDto.BroadcastReceived -> LogRowSpec(
        "", "←", false, "BC", UvpColor.Info,
        "语音广播请求 · source=${ev.sourceId}", highlight = true, category = "BROADCAST"
    )
    is SimEventDto.BroadcastInvited -> LogRowSpec(
        "", "→", true, "INV", UvpColor.Primary,
        "反向 INVITE → ${ev.platformUri} · 本地端口 ${ev.localPort}", category = "BROADCAST"
    )
    is SimEventDto.BroadcastStarted -> LogRowSpec(
        "", "♪", false, "RX", UvpColor.Success,
        "对讲音频开始 · 首包 ${ev.firstPacketDelayMs}ms", category = "BROADCAST"
    )
    is SimEventDto.BroadcastPacketRx -> null  // 媒体接收统计,不刷 SIP 信令日志(浮动/指示器读 currentBroadcast)
    is SimEventDto.BroadcastEnded -> LogRowSpec(
        "", "■", true, "END", UvpColor.TextHint,
        "对讲结束 · ${ev.reason} · ${ev.durationMs}ms", category = "BROADCAST"
    )
    is SimEventDto.NetworkBound -> LogRowSpec(
        "", "↔", true, "NET", UvpColor.Info,
        "网络 → ${ev.preference} · ${ev.interfaceName} · ${ev.localIp}", category = "NETWORK"
    )
    is SimEventDto.NetworkUnavailable -> LogRowSpec(
        "", "⚠", true, "NET", UvpColor.Danger,
        "网络不可用 · ${ev.reason}", category = "NETWORK"
    )
    SimEventDto.NetworkAuto -> LogRowSpec(
        "", "↔", true, "NET", UvpColor.TextHint,
        "网络偏好 → 自动(系统路由)", category = "NETWORK"
    )
}

private fun alarmResetBy(by: ResetSourceDto): String = when (by) {
    is ResetSourceDto.Local -> "本地"
    is ResetSourceDto.Remote -> "平台 ${by.subscriber}"
}

private fun msgMethodShort(m: SipMessageDto): String = when (m) {
    is SipMessageDto.Request -> when (m.method) {
        SipMethodDto.REGISTER -> "REG"
        SipMethodDto.INVITE -> "INV"
        SipMethodDto.MESSAGE -> "MSG"
        SipMethodDto.BYE -> "BYE"
        else -> m.method.name.take(3)
    }
    is SipMessageDto.Response -> "${m.statusCode}"
}

private fun msgContent(m: SipMessageDto): String = when (m) {
    is SipMessageDto.Request -> "${m.method.name} ${m.requestUri.take(30)}"
    is SipMessageDto.Response -> "${m.statusCode} ${m.reasonPhrase}"
}

private fun msgCategory(m: SipMessageDto): String = when (m) {
    is SipMessageDto.Request -> m.method.name
    is SipMessageDto.Response -> {
        val cseq = m.headers.firstOrNull {
            it.name.equals("CSeq", ignoreCase = true)
        }?.value ?: ""
        when {
            cseq.contains("REGISTER") -> "REGISTER"
            cseq.contains("INVITE") -> "INVITE"
            cseq.contains("MESSAGE") -> "MESSAGE"
            cseq.contains("BYE") -> "BYE"
            else -> ""
        }
    }
}

internal fun filterEvents(events: List<SimEventDto>, filter: String): List<SimEventDto> {
    if (filter == "全部") return events
    return events.filter { ev ->
        val spec = logRowSpec(ev) ?: return@filter false
        spec.category == filter
    }
}
