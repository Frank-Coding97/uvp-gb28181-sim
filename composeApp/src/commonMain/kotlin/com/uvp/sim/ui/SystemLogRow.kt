package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.ui.model.SystemLogDto
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SystemLogTab 单行渲染:level badge + tag chip + 消息 + 错误复制按钮 + 展开详情。
 *
 * 拆出原因:SystemLogTab.kt 主文件 > 400 行,行渲染 + 格式化函数挪出。
 */

@Composable
internal fun SystemLogRow(log: SystemLogDto, expanded: Boolean, onClick: () -> Unit) {
    val levelColor = when (log.level) {
        LogLevel.Debug -> UvpColor.TextHint
        LogLevel.Info -> UvpColor.Primary
        LogLevel.Warning -> UvpColor.Warning
        LogLevel.Error -> UvpColor.Danger
    }
    val clipboard = LocalClipboardManager.current
    val toast = LocalToastHost.current
    val isError = log.level == LogLevel.Warning || log.level == LogLevel.Error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatHms(log.timestampMs),
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(levelColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    log.level.short,
                    fontSize = 9.sp,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .background(UvpColor.Surface, RoundedCornerShape(3.dp))
                    .border(1.dp, UvpColor.Border, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    log.tag.display,
                    fontSize = 9.sp,
                    color = UvpColor.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                log.message,
                fontSize = 11.sp,
                color = UvpColor.Text,
                modifier = Modifier.weight(1f),
                maxLines = if (expanded) 10 else 1
            )
            if (isError) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(UvpColor.Surface, RoundedCornerShape(3.dp))
                        .border(1.dp, levelColor.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(formatLogForCopy(log)))
                            toast.success("错误已复制到剪贴板")
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制错误",
                            modifier = Modifier.size(11.dp),
                            tint = levelColor
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "复制",
                            fontSize = 9.sp,
                            color = levelColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        if (expanded) {
            val detail = log.detail
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UvpColor.Surface, RoundedCornerShape(4.dp))
                        .border(1.dp, UvpColor.Border, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        detail,
                        fontSize = 10.sp,
                        color = UvpColor.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

internal fun formatLogForCopy(log: SystemLogDto): String = buildString {
    append('[').append(formatHms(log.timestampMs)).append("] ")
    append('[').append(log.level.short).append("] ")
    append('[').append(log.tag.display).append("] ")
    append(log.message)
    val d = log.detail
    if (!d.isNullOrBlank()) {
        append('\n')
        append(d)
    }
}

internal fun formatHms(epochMs: Long): String {
    if (epochMs <= 0) return "--:--:--"
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${pad(ldt.hour, 2)}:${pad(ldt.minute, 2)}:${pad(ldt.second, 2)}"
}
