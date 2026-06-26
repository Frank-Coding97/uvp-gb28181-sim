package com.uvp.sim.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioCodec
import com.uvp.sim.config.VideoCodec
import com.uvp.sim.ui.InlineSegmented
import com.uvp.sim.ui.UvpColor

// ============= Codec card =============

@Composable
internal fun CodecCard(
    videoCodec: VideoCodec,
    audioCodec: AudioCodec,
    audioSampleRate: Int,
    locked: Boolean,
    onVideo: (VideoCodec) -> Unit,
    onAudio: (AudioCodec) -> Unit,
    onSampleRate: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("编码", fontSize = 12.sp, color = UvpColor.TextHint,
            fontWeight = FontWeight.Medium)
        InlineSegmented(
            label = "视频编码",
            active = videoCodec.label,
            options = VideoCodec.entries.map { it.label },
            enabled = !locked
        ) { picked -> onVideo(VideoCodec.entries.first { it.label == picked }) }
        InlineSegmented(
            label = "音频编码",
            active = audioCodec.label,
            options = AudioCodec.entries.map { it.label },
            enabled = !locked
        ) { picked -> onAudio(AudioCodec.entries.first { it.label == picked }) }

        // 采样率:G.711 强制 8000(disabled),AAC 可选 8000 / 16000。
        // 显示有效值 — 选 G.711 时即便存的是 16000,这里也亮 "8000"。
        val isAac = audioCodec == AudioCodec.AAC
        val effectiveRate = if (isAac) audioSampleRate else 8000
        Column {
            InlineSegmented(
                label = "音频采样率",
                active = effectiveRate.toString(),
                options = listOf("8000", "16000"),
                enabled = !locked && isAac
            ) { picked ->
                onSampleRate(picked.toIntOrNull() ?: 16000)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (isAac) "Hz · AAC 推荐 16000(更亮的音质)"
                else "Hz · G.711 协议固定 8000",
                fontSize = 10.sp, color = UvpColor.TextHint, maxLines = 1
            )
        }
    }
}
