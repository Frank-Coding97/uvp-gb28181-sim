package com.uvp.sim.ui.media

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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.ui.InlineSegmented
import com.uvp.sim.ui.UvpColor

// ============= Advanced (collapsed) =============

@Composable
internal fun AdvancedCard(
    open: Boolean,
    onToggle: () -> Unit,
    resolution: VideoResolution,
    frameRate: String,
    bitrate: String,
    gop: String,
    locked: Boolean,
    onResolution: (VideoResolution) -> Unit,
    onFrameRate: (String) -> Unit,
    onBitrate: (String) -> Unit,
    onGop: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null,
                modifier = Modifier.size(14.dp), tint = UvpColor.TextHint)
            Spacer(Modifier.width(6.dp))
            Text("自定义参数", fontSize = 12.sp,
                fontWeight = FontWeight.Medium, color = UvpColor.TextHint)
            Spacer(Modifier.width(8.dp))
            Text("分辨率 / 帧率 / 码率 / 关键帧间隔",
                fontSize = 10.5.sp, color = UvpColor.TextHint, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = UvpColor.TextHint
            )
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InlineSegmented(
                    label = "采集分辨率",
                    active = resolution.label,
                    options = VideoResolution.entries.map { it.label },
                    enabled = !locked
                ) { picked ->
                    onResolution(VideoResolution.entries.first { it.label == picked })
                }
                LabeledChipRow(
                    label = "帧率(fps)",
                    suffix = "通常 25",
                    options = FRAME_RATE_OPTIONS,
                    active = frameRate,
                    enabled = !locked,
                    onPick = onFrameRate
                )
                LabeledChipRow(
                    label = "码率(kbps)",
                    suffix = "越大越清晰、越占带宽",
                    options = BITRATE_OPTIONS,
                    active = bitrate,
                    enabled = !locked,
                    onPick = onBitrate
                )
                LabeledChipRow(
                    label = "关键帧间隔(秒)",
                    suffix = "越小越清晰但占带宽",
                    options = GOP_OPTIONS,
                    active = gop,
                    enabled = !locked,
                    onPick = onGop
                )
            }
        }
    }
}

private val FRAME_RATE_OPTIONS = listOf("15", "20", "25", "30")
private val BITRATE_OPTIONS = listOf("600", "1200", "2000", "4000", "6000", "8000")
private val GOP_OPTIONS = listOf("1", "2", "4")

@Composable
private fun LabeledChipRow(
    label: String,
    suffix: String,
    options: List<String>,
    active: String,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 11.sp, color = UvpColor.TextSecondary,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            options.forEach { value ->
                ChipCell(
                    text = value,
                    selected = value == active,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(value) }
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(suffix, fontSize = 10.sp, color = UvpColor.TextHint, maxLines = 1)
    }
}

@Composable
private fun ChipCell(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> UvpColor.BorderLight
        selected -> UvpColor.Primary
        else -> UvpColor.Border
    }
    val bg = if (selected && enabled) UvpColor.PrimaryLight else UvpColor.Surface
    val fg = when {
        !enabled -> UvpColor.TextHint
        selected -> UvpColor.Primary
        else -> UvpColor.TextSecondary
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            fontFamily = FontFamily.Monospace
        )
    }
}
