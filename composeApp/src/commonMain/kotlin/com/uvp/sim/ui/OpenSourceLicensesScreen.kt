package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 开源许可 — 列出 APK 依赖的三方开源组件 + 许可证类型 + 上游仓库。
 *
 * 满足 Apache License 2.0 §4(d):分发派生作品时须提供 LICENSE 副本 / 出处声明。
 * MIT / SIL OFL 1.1 / EPL-1.0 也要求归属声明,统一在这一页承载。
 */
@Composable
fun OpenSourceLicensesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LicensePreamble()
        LicenseGroup("Kotlin 生态", KOTLIN_LICENSES)
        LicenseGroup("Compose Multiplatform", COMPOSE_LICENSES)
        LicenseGroup("AndroidX", ANDROIDX_LICENSES)
        LicenseGroup("网络 / 序列化", NETWORK_LICENSES)
        LicenseGroup("媒体 / 图形", MEDIA_LICENSES)
        LicenseGroup("字体资源", FONT_LICENSES)
        Spacer(Modifier.height(8.dp))
        Text(
            "如遗漏归属条目请通过 About 页联系方式反馈,项目会立即补上。",
            fontSize = 10.sp,
            color = UvpColor.TextHint,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LicensePreamble() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.PrimaryLight)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "开源许可归属",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.Text,
        )
        Text(
            "UVP GB28181 Sim 采用 MIT 许可证发布。构建产物同时包含以下第三方开源组件,分别遵循各自许可证。点击条目跳转至上游仓库查看完整 LICENSE。",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
        )
    }
}

@Composable
private fun LicenseGroup(title: String, entries: List<LicenseEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = UvpColor.TextSecondary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(UvpColor.Surface)
                .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp)),
        ) {
            entries.forEachIndexed { i, e ->
                if (i > 0) LicenseDivider()
                LicenseRow(e)
            }
        }
    }
}

@Composable
private fun LicenseRow(entry: LicenseEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(entry.url) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.license, fontSize = 11.sp, color = UvpColor.Primary)
                Spacer(Modifier.width(6.dp))
                Text("·", fontSize = 11.sp, color = UvpColor.TextHint)
                Spacer(Modifier.width(6.dp))
                Text(entry.owner, fontSize = 11.sp, color = UvpColor.TextSecondary)
            }
        }
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = UvpColor.TextHint,
        )
    }
}

@Composable
private fun LicenseDivider() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UvpColor.BorderLight),
    )
}

private data class LicenseEntry(
    val name: String,
    val owner: String,
    val license: String,
    val url: String,
)

private val KOTLIN_LICENSES = listOf(
    LicenseEntry("Kotlin", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
    LicenseEntry("kotlinx.coroutines", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LicenseEntry("kotlinx.serialization", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LicenseEntry("kotlinx-datetime", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx-datetime"),
    LicenseEntry("kotlinx-io", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx-io"),
)

private val COMPOSE_LICENSES = listOf(
    LicenseEntry("Compose Multiplatform", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/compose-multiplatform"),
    LicenseEntry("Material 3 · Material Icons Extended", "JetBrains / Google", "Apache License 2.0", "https://github.com/JetBrains/compose-multiplatform"),
)

private val ANDROIDX_LICENSES = listOf(
    LicenseEntry("AndroidX Activity · Lifecycle · Core", "Google (Android Open Source Project)", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    LicenseEntry("AndroidX CameraX", "Google", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    LicenseEntry("AndroidX DataStore", "Google", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/datastore"),
    LicenseEntry("AndroidX Media3 (ExoPlayer)", "Google", "Apache License 2.0", "https://github.com/androidx/media"),
    LicenseEntry("AndroidX Security Crypto", "Google", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/security"),
    LicenseEntry("AndroidX Core SplashScreen · ProfileInstaller", "Google", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
)

private val NETWORK_LICENSES = listOf(
    LicenseEntry("Ktor", "JetBrains", "Apache License 2.0", "https://github.com/ktorio/ktor"),
    LicenseEntry("xmlutil", "Peter de Vrieze", "Apache License 2.0", "https://github.com/pdvrieze/xmlutil"),
)

private val MEDIA_LICENSES = listOf(
    LicenseEntry("Filament · gltfio", "Google", "Apache License 2.0", "https://github.com/google/filament"),
)

private val FONT_LICENSES = listOf(
    LicenseEntry("Source Han Sans SC (思源黑体)", "Adobe / Google", "SIL Open Font License 1.1", "https://github.com/adobe-fonts/source-han-sans"),
)
