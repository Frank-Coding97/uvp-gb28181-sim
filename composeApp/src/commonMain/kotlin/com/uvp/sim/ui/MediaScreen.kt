package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioCodec
import com.uvp.sim.config.VideoCodec
import com.uvp.sim.config.VideoQualityPreset
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.ui.media.CodecCard
import com.uvp.sim.ui.media.LockedHint
import com.uvp.sim.ui.media.PresetCard
import com.uvp.sim.ui.media.SummaryCard
import com.uvp.sim.ui.model.SipStateDto

/**
 * 音视频设置页 — A 方案:预设卡 + 自定义折叠。
 *
 * 普通用户视线:摘要 → 选个画质档 → 编码默认 H.264 + G.711A → 保存。
 * 老司机:展开"自定义参数",细调分辨率/帧率/码率/I 帧间隔。
 */
@Composable
fun MediaScreen(state: AppUiState, actions: AppActions) {
    val scroll = rememberScrollState()
    val toast = LocalToastHost.current
    val locked = state.sip == SipStateDto.Registered ||
        state.sip == SipStateDto.InCall ||
        state.sip == SipStateDto.Registering

    var resolution by remember(state.config) { mutableStateOf(state.config.video.resolution) }
    var frameRate by remember(state.config) { mutableStateOf(state.config.video.frameRate.toString()) }
    var bitrate by remember(state.config) { mutableStateOf(state.config.video.bitrateKbps.toString()) }
    var gop by remember(state.config) { mutableStateOf(state.config.video.keyframeIntervalSeconds.toString()) }
    var videoCodec by remember(state.config) { mutableStateOf(state.config.video.videoCodec) }
    var audioCodec by remember(state.config) { mutableStateOf(state.config.video.audioCodec) }
    var audioSampleRate by remember(state.config) {
        mutableStateOf(state.config.video.audioSampleRateHz)
    }
    var advancedOpen by remember { mutableStateOf(false) }

    // 当前选中的预设(如果数字组合恰好命中)
    val activePreset: VideoQualityPreset? = VideoQualityPreset.entries.firstOrNull {
        it.resolution == resolution &&
            it.frameRate == (frameRate.toIntOrNull() ?: -1) &&
            it.bitrateKbps == (bitrate.toIntOrNull() ?: -1) &&
            it.keyframeIntervalSeconds == (gop.toIntOrNull() ?: -1)
    }

    fun applyPreset(p: VideoQualityPreset) {
        resolution = p.resolution
        frameRate = p.frameRate.toString()
        bitrate = p.bitrateKbps.toString()
        gop = p.keyframeIntervalSeconds.toString()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryCard(
            preset = activePreset,
            resolution = resolution,
            frameRate = frameRate.toIntOrNull() ?: 0,
            bitrateKbps = bitrate.toIntOrNull() ?: 0,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate
        )
        if (locked) LockedHint()
        PresetCard(activePreset, locked, ::applyPreset)
        CodecCard(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate,
            locked = locked,
            onVideo = { videoCodec = it },
            onAudio = { picked ->
                audioCodec = picked
                // G.711 强制 8000;切到 AAC 时若先前是 8000 保留,否则恢复 16000
                if (picked != AudioCodec.AAC) audioSampleRate = 8000
                else if (audioSampleRate !in setOf(8000, 16000)) audioSampleRate = 16000
            },
            onSampleRate = { audioSampleRate = it }
        )
        AdvancedCard(
            open = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
            resolution = resolution,
            frameRate = frameRate,
            bitrate = bitrate,
            gop = gop,
            locked = locked,
            onResolution = { resolution = it },
            onFrameRate = { frameRate = it },
            onBitrate = { bitrate = it },
            onGop = { gop = it }
        )
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
                            audioCodec = audioCodec,
                            audioSampleRateHz = audioSampleRate
                        )
                    )
                )
                toast.success("音视频参数已保存")
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (locked) "注销后修改" else "保存",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) UvpColor.TextHint else Color.White,
                letterSpacing = 2.sp
            )
        }
    }
}

// ============= Advanced (collapsed) =============

@Composable
private fun AdvancedCard(
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

