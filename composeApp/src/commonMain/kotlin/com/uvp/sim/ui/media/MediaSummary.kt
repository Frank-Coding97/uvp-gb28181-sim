package com.uvp.sim.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioCodec
import com.uvp.sim.config.VideoCodec
import com.uvp.sim.config.VideoQualityPreset
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.ui.UvpColor

// ============= Summary =============

@Composable
internal fun SummaryCard(
    preset: VideoQualityPreset?,
    resolution: VideoResolution,
    frameRate: Int,
    bitrateKbps: Int,
    videoCodec: VideoCodec,
    audioCodec: AudioCodec,
    audioSampleRate: Int
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            UvpColor.Primary.copy(alpha = 0.10f),
            UvpColor.Primary.copy(alpha = 0.04f)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient, RoundedCornerShape(10.dp))
            .border(
                1.5.dp,
                UvpColor.Primary.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(UvpColor.Primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    preset?.label ?: "自定义",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("当前画质", fontSize = 12.sp, color = UvpColor.TextSecondary,
                fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${resolution.label} · ${frameRate}fps · ${bitrateKbps}kbps",
            fontSize = 15.sp, color = UvpColor.Text,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        val audioRateLabel = when (audioCodec) {
            AudioCodec.G711A, AudioCodec.G711U -> "8000Hz"
            AudioCodec.AAC -> "${audioSampleRate}Hz"
        }
        Text(
            "视频 ${videoCodec.label} · 音频 ${audioCodec.label} · $audioRateLabel",
            fontSize = 11.5.sp, color = UvpColor.TextSecondary
        )
    }
}

// ============= Locked hint =============

@Composable
internal fun LockedHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.WarningBg, RoundedCornerShape(6.dp))
            .border(1.dp, UvpColor.WarningBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("已注册时不可修改音视频参数,注销后再调整",
            fontSize = 12.sp, color = UvpColor.Warning)
    }
}
