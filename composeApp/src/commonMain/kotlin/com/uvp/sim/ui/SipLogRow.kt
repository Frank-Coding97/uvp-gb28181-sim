package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipMessageDto
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SipLogListView 单行渲染 + 原始 SIP 报文格式化 + method badge。
 *
 * 拆出原因:SipLogListView.kt 主文件 > 400 行,行渲染独立成块。
 */

@Composable
internal fun LogRow(ev: SimEventDto, expanded: Boolean, onClick: () -> Unit) {
    val spec = logRowSpec(ev) ?: return
    val time = formatHmsList(ev.timestampMs)
    val clipboard = LocalClipboardManager.current
    val toast = LocalToastHost.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .run { if (spec.highlight) background(UvpColor.WarningBg) else this }
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, fontSize = 10.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Text(
                spec.arrow,
                fontSize = 10.sp,
                color = if (spec.outgoing) UvpColor.Primary else UvpColor.Success,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            MethodBadge(spec.method, spec.badgeColor)
            Spacer(Modifier.width(8.dp))
            Text(
                spec.content,
                fontSize = 11.sp,
                color = UvpColor.Text,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) 8 else 1,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            val raw = rawSipBody(ev)
            if (raw.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // 工具栏:右对齐复制按钮(避免遮挡 raw 文本起头)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(UvpColor.Bg, RoundedCornerShape(3.dp))
                            .border(1.dp, UvpColor.Border, RoundedCornerShape(3.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(raw))
                                toast.success("已复制到剪贴板")
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制 SIP 报文",
                                modifier = Modifier.size(11.dp),
                                tint = UvpColor.TextSecondary
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "复制",
                                fontSize = 9.sp,
                                color = UvpColor.TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UvpColor.Surface, RoundedCornerShape(4.dp))
                        .border(1.dp, UvpColor.Border, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        raw,
                        fontSize = 10.sp,
                        color = UvpColor.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MethodBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun rawSipBody(ev: SimEventDto): String {
    val msg = when (ev) {
        is SimEventDto.MessageSent -> ev.message
        is SimEventDto.MessageReceived -> ev.message
        else -> return ""
    }
    return buildString {
        when (msg) {
            is SipMessageDto.Request -> appendLine("${msg.method} ${msg.requestUri} SIP/2.0")
            is SipMessageDto.Response -> appendLine("SIP/2.0 ${msg.statusCode} ${msg.reasonPhrase}")
        }
        msg.headers.forEach { appendLine("${it.name}: ${it.value}") }
        if (msg.body.isNotEmpty()) {
            appendLine()
            append(msg.body.take(800))
        }
    }
}

internal fun formatHmsList(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(ldt.hour, ldt.minute, ldt.second)
}
