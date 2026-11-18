package com.uvp.sim.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.PlatformBuildInfo
import com.uvp.sim.ui.UvpColor
import kotlinx.coroutines.launch

/**
 * 检查更新 —— 在线升级的唯一入口。
 *
 * 产品口径（与桌面端一致）：
 * - 启动时只静默检查 + 一句提示，绝不自动安装：安装会重启 App，正在跑的
 *   SIP 注册 / 推流 / 压测会全部中断；
 * - 这里由用户主动点「立即升级」，下载完成后交给系统安装器，最终确认仍在
 *   系统弹窗里完成（侧载应用的硬约束，绕不过去）。
 */
@Composable
fun UpdateScreen() {
    val state by UpdateCenter.state.collectAsState()
    val toast = LocalToastHost.current
    val scope = rememberCoroutineScope()

    // 首次进入且还没查过 → 自动查一次；已有结果（比如启动时静默查到的）
    // 就沿用，避免用户刚点进来又看到一次 loading。
    LaunchedEffect(Unit) {
        if (UpdateCenter.supported && state is UpdateState.Idle) UpdateCenter.check()
    }

    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val manifest = when (val snapshot = state) {
        is UpdateState.Available -> snapshot.manifest
        is UpdateState.Downloading -> snapshot.manifest
        is UpdateState.ReadyToInstall -> snapshot.manifest
        is UpdateState.NeedPermission -> snapshot.manifest
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VersionCard(state = state, manifest = manifest)

        if (manifest != null && manifest.notes.isNotBlank()) {
            SectionTitle("更新说明")
            NotesCard(manifest.notes)
        }

        if (state is UpdateState.Downloading) {
            DownloadProgress(state = state as UpdateState.Downloading)
        }

        Button(
            onClick = {
                when (state) {
                    is UpdateState.Available,
                    is UpdateState.ReadyToInstall,
                    is UpdateState.NeedPermission -> scope.launch { UpdateCenter.install() }
                    else -> scope.launch { UpdateCenter.check() }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = primaryActionLabel(state),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        when (val snapshot = state) {
            is UpdateState.Failed -> Text(
                text = snapshot.message,
                fontSize = 12.sp,
                color = UvpColor.DangerText,
            )
            is UpdateState.NeedPermission -> Text(
                text = "已打开系统设置。请允许本应用「安装未知应用」，然后返回这里继续安装。",
                fontSize = 12.sp,
                color = UvpColor.Warning,
            )
            else -> Unit
        }

        WarningCard()
    }
}

@Composable
private fun VersionCard(state: UpdateState, manifest: com.uvp.sim.update.UpdateManifest?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VersionRow(
            title = "当前版本",
            value = "v${PlatformBuildInfo.versionName}（build ${PlatformBuildInfo.versionCode}）",
            highlight = false,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
        VersionRow(
            title = "最新版本",
            value = latestVersionText(state, manifest),
            highlight = state is UpdateState.Available ||
                state is UpdateState.Downloading ||
                state is UpdateState.ReadyToInstall ||
                state is UpdateState.NeedPermission,
        )
        val hint = statusHint(state)
        if (hint != null) {
            Text(text = hint, fontSize = 12.sp, color = UvpColor.TextSecondary)
        }
    }
}

@Composable
private fun VersionRow(title: String, value: String, highlight: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, color = UvpColor.Text)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) UvpColor.Primary else UvpColor.TextSecondary,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextSecondary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun NotesCard(notes: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                Text(
                    text = line.removePrefix("- ").removePrefix("* ").let { "· $it" },
                    fontSize = 12.sp,
                    color = UvpColor.TextSecondary,
                    lineHeight = 18.sp,
                )
            }
    }
}

@Composable
private fun DownloadProgress(state: UpdateState.Downloading) {
    val fraction = if (state.total > 0) {
        (state.downloaded.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(UvpColor.BorderLight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(UvpColor.Primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "正在下载 ${formatBytes(state.downloaded)}",
                fontSize = 11.sp,
                color = UvpColor.TextSecondary,
            )
            Text(
                text = if (state.total > 0) {
                    "${(fraction * 100).toInt()}% / ${formatBytes(state.total)}"
                } else {
                    "大小未知"
                },
                fontSize = 11.sp,
                color = UvpColor.TextHint,
            )
        }
    }
}

@Composable
private fun WarningCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.WarningBg)
            .border(1.dp, UvpColor.WarningBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("升级前请知悉", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UvpColor.Warning)
        Text(
            "· 安装时系统会弹窗确认；首次升级需要在系统设置里允许本应用「安装未知应用」。",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
            lineHeight = 17.sp,
        )
        Text(
            "· 安装完成后应用会重启，正在进行中的设备注册、推流、录像会中断，建议在空闲时段升级。",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
            lineHeight = 17.sp,
        )
        Text(
            "· 升级包只从官方域名下载并校验签名，请勿安装来路不明的安装包。",
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
            lineHeight = 17.sp,
        )
    }
}

private fun primaryActionLabel(state: UpdateState): String = when (state) {
    is UpdateState.Checking -> "正在检查…"
    is UpdateState.Downloading -> "正在下载…"
    is UpdateState.Available -> "立即升级"
    is UpdateState.ReadyToInstall -> "继续安装"
    is UpdateState.NeedPermission -> "我已授权，继续安装"
    else -> "检查更新"
}

private fun latestVersionText(
    state: UpdateState,
    manifest: com.uvp.sim.update.UpdateManifest?,
): String = when (state) {
    UpdateState.Latest -> "已是最新版本"
    UpdateState.Checking -> "检查中…"
    UpdateState.Idle -> "未检查"
    is UpdateState.Failed -> "检查失败"
    else -> manifest?.let { "v${it.trimmedVersion()}" } ?: "—"
}

private fun statusHint(state: UpdateState): String? = when (state) {
    is UpdateState.Available -> "发现新版本，可点击下方按钮升级。"
    is UpdateState.Downloading -> "下载中，请保持网络畅通。"
    is UpdateState.ReadyToInstall -> "安装包已就绪。若系统安装器被关闭，可再次点击「继续安装」。"
    is UpdateState.NeedPermission -> "等待系统授权。"
    UpdateState.Latest -> "当前已是最新版本，无需升级。"
    is UpdateState.Failed -> "请检查网络或稍后重试。"
    else -> null
}

/** 字节数 → 易读体积；总量未知时调用方不应依赖它。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    val text = if (index == 0) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return "$text ${units[index]}"
}
