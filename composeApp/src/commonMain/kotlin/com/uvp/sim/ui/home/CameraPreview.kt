package com.uvp.sim.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SipStateDto

/**
 * 主屏视频预览块 — 注册后显示 [PlatformCameraPreview],未注册时显示 [BrandCover] 品牌封面。
 * 录像中叠加左上角 [RecordingBadge](红点 + 计时器)。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun CameraPreviewBox(state: AppUiState) {
    val showPreview = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    val isRecording = state.recording.isRecording
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (showPreview) {
                    Brush.linearGradient(listOf(Color(0xFF1F2937), Color(0xFF1F2937)))
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0B1E3F), Color(0xFF0F2A57), Color(0xFF1A4480)),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showPreview) {
            PlatformCameraPreview(modifier = Modifier.fillMaxSize())
        } else {
            BrandCover()
        }
        // 录像红点(左上角):脉动闪烁 + REC + 计时器
        if (isRecording) {
            RecordingBadge(
                startMs = state.recording.startMs,
                source = state.recording.source,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
        // 注:分辨率/推流状态 chip 已上移到 StatusBanner,视频框顶部留给烧进流的 OSD
    }
}

/**
 * 录像标徽 — 脉动红点 + REC + 计时器(mm:ss)。
 *
 * 放在视频预览区左上角,不挡现有 LIVE 标(右上角)。脉动用 infiniteTransition
 * 让 alpha 在 1.0 ↔ 0.35 之间 800ms 周期循环,跟相机录像机的视觉惯例一致。
 */
@Composable
private fun RecordingBadge(
    startMs: Long?,
    source: com.uvp.sim.ui.model.RecordSourceDto?,
    modifier: Modifier = Modifier
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "rec-alpha"
    )
    // 计时器:每秒重组一次,显示从 startMs 到现在的 mm:ss
    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(startMs) {
        if (startMs == null) return@LaunchedEffect
        while (true) {
            elapsedSec = ((kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs) / 1000)
                .coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    val mm = (elapsedSec / 60).toString().padStart(2, '0')
    val ss = (elapsedSec % 60).toString().padStart(2, '0')
    val sourceLabel = when (source) {
        com.uvp.sim.ui.model.RecordSourceDto.PlatformCmd -> "REC·平台"
        else -> "REC"
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(UvpColor.Danger.copy(alpha = alpha))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$sourceLabel  $mm:$ss",
            fontSize = 10.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BrandCover() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // GB/T 28181 chip
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    "GB/T 28181-2022",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            // UVP 渐变大字
            Text(
                "UVP",
                fontSize = 60.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 10.sp,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFF7CC4FF))
                    )
                )
            )
        }
        Text(
            "注册后开启预览",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        )
    }
}
