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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 关于 — 展示版本 / 协议 / 开源许可 / 仓库地址 / 联系方式。
 *
 * 版本号从 [PlatformBuildInfo] 拿(Android PackageManager,iOS NSBundle),
 * 仓库外链走 [openUrl] 交给系统浏览器,微信号复制到剪贴板 + toast 提示。
 */
@Composable
fun AboutScreen() {
    val toast = LocalToastHost.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AboutHero()

        AboutSectionCard {
            AboutLineRow(
                icon = Icons.Outlined.Verified,
                title = "版本",
                value = "v${PlatformBuildInfo.versionName}  (build ${PlatformBuildInfo.versionCode})",
            )
            AboutLineDivider()
            AboutLineRow(
                icon = Icons.Outlined.Videocam,
                title = "协议",
                value = "GB/T 28181-2022",
            )
            AboutLineDivider()
            AboutLineRow(
                icon = Icons.Outlined.Description,
                title = "开源协议",
                value = "MIT License · 完全开源",
            )
        }

        AboutSectionTitle("源码仓库")
        AboutSectionCard {
            AboutLinkRow(
                icon = Icons.Outlined.Code,
                title = "GitHub",
                value = "github.com/Frank-Coding97/uvp-gb28181-sim",
                onClick = { openUrl("https://github.com/Frank-Coding97/uvp-gb28181-sim") },
            )
            AboutLineDivider()
            AboutLinkRow(
                icon = Icons.Outlined.Code,
                title = "Gitee",
                value = "gitee.com/Frank-Coding/uvp-gb28181-sim",
                onClick = { openUrl("https://gitee.com/Frank-Coding/uvp-gb28181-sim") },
            )
        }

        AboutSectionTitle("联系作者")
        AboutSectionCard {
            AboutCopyRow(
                icon = Icons.Outlined.ContentCopy,
                title = "微信",
                value = "Mtudou123",
                onClick = {
                    clipboard.setText(AnnotatedString("Mtudou123"))
                    toast.success("微信号已复制,可去微信搜索添加")
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "© 2026 UVP · 通用 GB/T 28181-2022 下级设备模拟器",
            fontSize = 10.sp,
            color = UvpColor.TextHint,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UvpColor.PrimaryLight)
            .padding(vertical = 20.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(UvpColor.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Videocam,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "UVP GB28181 模拟器",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.Text,
        )
        Text(
            "通用 GB/T 28181-2022 下级设备模拟器",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
        )
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun AboutSectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp)),
    ) {
        content()
    }
}

@Composable
private fun AboutLineRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    AboutRowScaffold(icon = icon, title = title) {
        Text(value, fontSize = 12.sp, color = UvpColor.TextSecondary)
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    AboutRowScaffold(icon = icon, title = title, onClick = onClick) {
        Text(value, fontSize = 12.sp, color = UvpColor.Primary)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = UvpColor.Primary,
        )
    }
}

@Composable
private fun AboutCopyRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    AboutRowScaffold(icon = icon, title = title, onClick = onClick) {
        Text(value, fontSize = 12.sp, color = UvpColor.Primary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = UvpColor.Primary,
        )
    }
}

@Composable
private fun AboutRowScaffold(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = UvpColor.TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 13.sp,
            color = UvpColor.Text,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailing()
        }
    }
}

@Composable
private fun AboutLineDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UvpColor.BorderLight)
    )
}
