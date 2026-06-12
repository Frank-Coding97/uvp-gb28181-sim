package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioCodec
import com.uvp.sim.config.VideoCodec
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.sip.SipState

/**
 * 音视频设置页 — 独立 tab,与"高级设置"分离。
 *
 * 6 字段:采集分辨率 / 视频编码 / 音频编码 / 帧率 / 码率 / I 帧间隔。
 * 已注册或注册中时整面板 disabled,以免在 streaming 中改参数导致重连。
 */
@Composable
fun MediaScreen(state: AppUiState, actions: AppActions) {
    val scroll = rememberScrollState()
    val locked = state.sip == SipState.Registered ||
        state.sip == SipState.InCall ||
        state.sip == SipState.Registering

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryCard(state)
        if (locked) LockedHint()
        VideoCard(state, actions, locked)
    }
}

@Composable
private fun SummaryCard(state: AppUiState) {
    val v = state.config.video
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("当前编码", fontSize = 12.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "${v.videoCodec.label} · ${v.resolution.label} · ${v.frameRate}fps · ${v.bitrateKbps}kbps",
            fontSize = 13.sp, color = UvpColor.Text, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${v.audioCodec.label} · GOP ${v.keyframeIntervalSeconds}s",
            fontSize = 11.5.sp, color = UvpColor.TextSecondary, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun LockedHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.WarningBg, RoundedCornerShape(6.dp))
            .border(1.dp, UvpColor.WarningBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "已注册时不可修改音视频参数,注销后再调整",
            fontSize = 12.sp, color = UvpColor.Warning
        )
    }
}

@Composable
private fun VideoCard(state: AppUiState, actions: AppActions, locked: Boolean) {
    var resolution by remember(state.config) { mutableStateOf(state.config.video.resolution) }
    var frameRate by remember(state.config) { mutableStateOf(state.config.video.frameRate.toString()) }
    var bitrate by remember(state.config) { mutableStateOf(state.config.video.bitrateKbps.toString()) }
    var gop by remember(state.config) { mutableStateOf(state.config.video.keyframeIntervalSeconds.toString()) }
    var videoCodec by remember(state.config) { mutableStateOf(state.config.video.videoCodec) }
    var audioCodec by remember(state.config) { mutableStateOf(state.config.video.audioCodec) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("采集与编码",
            fontSize = 12.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)

        InlineSegmented(
            label = "采集分辨率",
            active = resolution.label,
            options = VideoResolution.entries.map { it.label },
            enabled = !locked
        ) { picked ->
            resolution = VideoResolution.entries.first { it.label == picked }
        }
        InlineSegmented(
            label = "视频编码",
            active = videoCodec.label,
            options = VideoCodec.entries.map { it.label },
            enabled = !locked
        ) { picked ->
            videoCodec = VideoCodec.entries.first { it.label == picked }
        }
        InlineSegmented(
            label = "音频编码",
            active = audioCodec.label,
            options = AudioCodec.entries.map { it.label },
            enabled = !locked
        ) { picked ->
            audioCodec = AudioCodec.entries.first { it.label == picked }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineField("帧率(fps)", frameRate,
                { frameRate = it.filter { c -> c.isDigit() } },
                Modifier.weight(1f), keyboard = KeyboardType.Number, enabled = !locked)
            InlineField("码率(kbps)", bitrate,
                { bitrate = it.filter { c -> c.isDigit() } },
                Modifier.weight(1f), keyboard = KeyboardType.Number, enabled = !locked)
        }
        InlineField("I 帧间隔(秒)", gop,
            { gop = it.filter { c -> c.isDigit() } },
            keyboard = KeyboardType.Number, enabled = !locked)

        Spacer(Modifier.height(2.dp))
        Button(
            enabled = !locked,
            onClick = {
                actions.onConfigSave(
                    state.config.copy(
                        video = state.config.video.copy(
                            resolution = resolution,
                            frameRate = frameRate.toIntOrNull()?.coerceIn(5, 60) ?: 25,
                            bitrateKbps = bitrate.toIntOrNull()?.coerceIn(100, 16000) ?: 2000,
                            keyframeIntervalSeconds = gop.toIntOrNull()?.coerceIn(1, 10) ?: 1,
                            videoCodec = videoCodec,
                            audioCodec = audioCodec
                        )
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (locked) "注销后修改" else "保存",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) UvpColor.TextHint else Color.White
            )
        }
    }
}
