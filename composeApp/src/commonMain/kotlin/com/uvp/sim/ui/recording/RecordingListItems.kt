package com.uvp.sim.ui.recording

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.RecordingThumbnail
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.RecordingFileDto

/** 单段录像卡片 — 缩略图 + 起止时间区间 + 时长/大小/来源 + 删除按钮. */
@Composable
internal fun RecordingCard(
    file: RecordingFileDto,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThumbnailBox(file.thumbnailPath)
        Column(modifier = Modifier.weight(1f)) {
            // 起止时间区间:09:56:53 → 09:57:13
            Text(
                "${formatTime(file.startTimeMs)} → ${formatTime(file.endTimeMs)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UvpColor.Text
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    formatDuration(file.durationMs),
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary
                )
                Text("·", fontSize = 11.sp, color = UvpColor.TextHint)
                Text(
                    formatSize(file.sizeBytes),
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary
                )
                Text("·", fontSize = 11.sp, color = UvpColor.TextHint)
                Text(
                    file.source.label(),
                    fontSize = 10.sp,
                    color = UvpColor.TextHint
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = UvpColor.Danger,
                modifier = Modifier.size(18.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThumbnailBox(path: String?) {
    Box(
        modifier = Modifier
            .width(80.dp)
            .height(45.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(UvpColor.BorderLight),
        contentAlignment = Alignment.Center
    ) {
        RecordingThumbnail(
            filePath = path,
            modifier = Modifier.fillMaxSize(),
            onMissing = {
                Icon(
                    Icons.Outlined.Movie, contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = UvpColor.TextHint
                )
            }
        )
    }
}

@Composable
internal fun DateHeader(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            date,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.TextSecondary
        )
    }
}

@Composable
internal fun EmptyHint(title: String, subtitle: String) {
    Column(
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
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = UvpColor.Text)
        Text(subtitle, fontSize = 11.sp, color = UvpColor.TextSecondary)
    }
}
