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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.VideoQualityPreset
import com.uvp.sim.ui.UvpColor

// ============= Preset card =============

@Composable
internal fun PresetCard(
    active: VideoQualityPreset?,
    locked: Boolean,
    onPick: (VideoQualityPreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("画质预设", fontSize = 12.sp, color = UvpColor.TextHint,
            fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoQualityPreset.entries.forEach { preset ->
                PresetTile(
                    preset = preset,
                    selected = preset == active,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(preset) }
                )
            }
        }
    }
}

@Composable
private fun PresetTile(
    preset: VideoQualityPreset,
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
    val bg = when {
        selected && enabled -> UvpColor.PrimaryLight
        else -> UvpColor.Surface
    }
    val titleColor = when {
        !enabled -> UvpColor.TextHint
        selected -> UvpColor.Primary
        else -> UvpColor.Text
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected && enabled) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(UvpColor.Primary))
                Spacer(Modifier.width(4.dp))
            }
            Text(preset.label, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor)
        }
        Text(
            preset.description,
            fontSize = 9.5.sp,
            color = if (enabled) UvpColor.TextHint else UvpColor.TextHint.copy(alpha = 0.6f),
            maxLines = 1,
            fontFamily = FontFamily.Monospace
        )
    }
}
