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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.ui.model.NetworkStateDto

/**
 * 设置 → 网络 子页:三选一 RadioGroup + 当前运行时状态诊断卡。
 *
 * iOS / 桌面分支显示"仅 Android 支持"说明,不出选择控件(系统不允许应用强制选网卡)。
 *
 * 偏好选择持久化在 SimConfig.network.preference,运行时绑定由 NetworkController 驱动。
 */
@Composable
fun NetworkSettingsPage(state: AppUiState, actions: AppActions) {
    if (!isNetworkSelectionSupported) {
        UnsupportedPlatformNotice()
        return
    }

    val current = state.config.network.preference
    val runtime = state.networkRuntimeState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NetworkOptionCard(
            icon = Icons.Outlined.AutoMode,
            title = "自动",
            description = "系统路由表决定(默认)",
            selected = current == NetworkPreference.AUTO,
            onClick = { actions.onNetworkPreferenceChange(NetworkPreference.AUTO) }
        )
        NetworkOptionCard(
            icon = Icons.Outlined.Wifi,
            title = "Wi-Fi",
            description = "强制走 Wi-Fi 网卡 · 无 Wi-Fi 时报不可用",
            selected = current == NetworkPreference.WIFI,
            onClick = { actions.onNetworkPreferenceChange(NetworkPreference.WIFI) }
        )
        NetworkOptionCard(
            icon = Icons.Outlined.SignalCellular4Bar,
            title = "蜂窝",
            description = "强制走 SIM 卡蜂窝 · 飞行模式/无 SIM 时报不可用",
            selected = current == NetworkPreference.CELLULAR,
            onClick = { actions.onNetworkPreferenceChange(NetworkPreference.CELLULAR) }
        )

        Spacer(Modifier.height(4.dp))

        NetworkRuntimeStateCard(runtime)
    }
}

@Composable
private fun NetworkOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) UvpColor.Primary else UvpColor.Border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) UvpColor.Primary else UvpColor.PrimaryLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) Color.White else UvpColor.Primary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) UvpColor.Primary else UvpColor.Text
            )
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 11.sp, color = UvpColor.TextSecondary)
        }
        if (selected) {
            Icon(
                Icons.Outlined.CheckCircle, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = UvpColor.Primary
            )
        }
    }
}

@Composable
private fun NetworkRuntimeStateCard(runtime: NetworkStateDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.BorderLight)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "当前状态",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.TextSecondary
        )
        when (runtime) {
            NetworkStateDto.Auto -> {
                Text("自动 · 由系统路由表决定", fontSize = 13.sp, color = UvpColor.Text)
            }
            is NetworkStateDto.Bound -> {
                Text(
                    "✓ 已绑定到 ${runtime.preference.name}",
                    fontSize = 13.sp,
                    color = UvpColor.Success,
                    fontWeight = FontWeight.Medium
                )
                Text("接口: ${runtime.interfaceName}", fontSize = 12.sp, color = UvpColor.TextSecondary)
                Text("IP: ${runtime.localIp}", fontSize = 12.sp, color = UvpColor.TextSecondary)
            }
            is NetworkStateDto.Unavailable -> {
                Text(
                    "⚠️ ${runtime.reason}",
                    fontSize = 13.sp,
                    color = UvpColor.Danger,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "偏好 ${runtime.preference.name} 当前没有可用网卡",
                    fontSize = 11.sp,
                    color = UvpColor.TextSecondary
                )
            }
            is NetworkStateDto.Switching -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = UvpColor.Primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "切换中: ${runtime.from.name} → ${runtime.to.name}",
                        fontSize = 13.sp,
                        color = UvpColor.Text
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedPlatformNotice() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.NetworkCheck,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = UvpColor.TextHint
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "仅 Android 支持网络选择",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
        }
        Text(
            "iOS 系统不允许应用强制选择网卡(Wi-Fi / 蜂窝)。" +
                "如果需要在 iOS 上测试不同网络出口下的国标注册,请在系统设置里手动开关 Wi-Fi。",
            fontSize = 13.sp,
            color = UvpColor.TextSecondary
        )
    }
}
