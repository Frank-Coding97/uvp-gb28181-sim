package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor

/**
 * 「能力」Tab 主屏 — 卡片化展示已实现的国标扩展能力。
 *
 * M2 范围内只放 1 张卡片:目录管理。后续可扩录像 Query / 报警订阅 / 设备控制
 * 子模块入口等。点卡片进入 [CatalogManagementScreen]。
 */
@Composable
fun CapabilityScreen(state: AppUiState, actions: AppActions) {
    var showCatalog by remember { mutableStateOf(false) }
    if (showCatalog) {
        CatalogManagementScreen(
            state = state,
            actions = actions,
            onBack = { showCatalog = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "能力",
            color = UvpColor.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        CatalogManagementCard(
            nodeCount = state.catalogTree.size,
            subscribed = state.subscriptions[SubscriptionKind.Catalog]?.active == true,
            remaining = state.subscriptions[SubscriptionKind.Catalog]?.remainingSeconds,
            notifyCount = state.subscriptions[SubscriptionKind.Catalog]?.notifyCount ?: 0,
            onClick = { showCatalog = true }
        )
    }
}

@Composable
private fun CatalogManagementCard(
    nodeCount: Int,
    subscribed: Boolean,
    remaining: Int?,
    notifyCount: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = UvpColor.Surface
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(UvpColor.Primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AccountTree,
                    contentDescription = null,
                    tint = UvpColor.Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "目录管理",
                    color = UvpColor.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "§9.3.1 设备目录订阅 · $nodeCount 个节点",
                    color = UvpColor.TextSecondary,
                    fontSize = 11.sp
                )
                if (subscribed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("已订阅")
                            if (remaining != null) append(" · 剩 ${remaining}s")
                            append(" · 推送 ${notifyCount}")
                        },
                        color = UvpColor.Success,
                        fontSize = 11.sp
                    )
                }
            }
            Icon(
                Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = UvpColor.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
