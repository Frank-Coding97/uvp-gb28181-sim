package com.uvp.sim.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor

/** 顶部筛选条 — 「筛选」按钮 + 「本周」快捷 + 当前筛选 label + 「清除」. */
@Composable
internal fun FilterBar(
    filterLabel: String,
    hasFilter: Boolean,
    onClickFilter: () -> Unit,
    onClickThisWeek: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(UvpColor.PrimaryLight, RoundedCornerShape(8.dp))
                .clickable { onClickFilter() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.FilterList, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = UvpColor.Primary
                )
                Text("筛选", color = UvpColor.Primary, fontSize = 13.sp)
            }
        }
        Box(
            modifier = Modifier
                .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
                .clickable { onClickThisWeek() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("本周", color = UvpColor.TextSecondary, fontSize = 12.sp)
        }
        Box(modifier = Modifier.weight(1f))
        Text(
            filterLabel,
            color = if (hasFilter) UvpColor.Primary else UvpColor.TextHint,
            fontSize = 11.sp,
            fontWeight = if (hasFilter) FontWeight.Medium else FontWeight.Normal
        )
        if (hasFilter) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clickable { onClear() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("清除", color = UvpColor.Danger, fontSize = 11.sp)
            }
        }
    }
}

/** 「筛选后 N 段 · 总大小 · 总时长」summary. */
@Composable
internal fun SummaryBar(
    count: Int,
    totalBytes: Long,
    totalDurationMs: Long,
) {
    val sizeText = formatBytes(totalBytes)
    val durationText = formatDurationShort(totalDurationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Bg)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$count 段", color = UvpColor.TextSecondary, fontSize = 11.sp)
        Text("·", color = UvpColor.TextHint, fontSize = 11.sp)
        Text(sizeText, color = UvpColor.TextSecondary, fontSize = 11.sp)
        Text("·", color = UvpColor.TextHint, fontSize = 11.sp)
        Text(durationText, color = UvpColor.TextSecondary, fontSize = 11.sp)
    }
}
