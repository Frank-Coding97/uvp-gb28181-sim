package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 录像页 — M2 占位。
 *
 * 真正实现见 spec `m2-recording-local`。M2 工作内容:
 * - MediaRecorder 复用 Camera 流编 MP4 落盘
 * - 录像状态机 Idle → Recording → AutoSplit → Saved
 * - 录像入库 (startTime/duration/channelId/filePath/sizeBytes)
 * - 列表 UI:日期分组、缩略图、时长、大小
 * - 时间筛选 + 通道关键字
 * - GB/T 28181 RecordInfo 应答 + INVITE PLAYBACK 回放(M3)
 */
@Composable
fun RecordingScreen(state: AppUiState, actions: AppActions) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, UvpColor.Border, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(UvpColor.PrimaryLight, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Videocam, contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = UvpColor.Primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "录像文件管理",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Text(
                "M2 上线",
                fontSize = 11.sp,
                color = UvpColor.Warning,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "录像列表 · 日期筛选 · 播放/导出 · 国标 RecordInfo 应答",
                fontSize = 11.sp,
                color = UvpColor.TextSecondary
            )
        }
    }
}
