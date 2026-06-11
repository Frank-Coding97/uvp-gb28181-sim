package com.uvp.sim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 工具 Tool screen — M1 占位.
 *
 * M2/M3 将放置:测试场景注入 / 协议合规体检 / 录制重放 / 数据看板
 * (参见 wiki/projects/uvp-gb28181-sim/research/competitors.md 的差异化清单)
 */
@Composable
fun ToolScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Build,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = UvpColor.TextHint
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "工具箱",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = UvpColor.TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "测试场景 / 协议体检 / 录制重放 / 数据看板",
            fontSize = 12.sp,
            color = UvpColor.TextHint
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "M2 — M3 上线",
            fontSize = 11.sp,
            color = UvpColor.TextHint
        )
    }
}
