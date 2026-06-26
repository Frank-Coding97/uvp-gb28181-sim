package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SipStateDto

/**
 * 主屏顶部状态 banner — 根据 SIP 状态选配色,右侧追加推流码率/分辨率 chip
 * 与心跳/失败原因等附加信息。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun StatusBanner(state: AppUiState) {
    val spec = when (state.sip) {
        SipStateDto.Registered, SipStateDto.InCall -> BannerSpec(
            UvpColor.SuccessBg, UvpColor.SuccessBorder, UvpColor.Success,
            "设备已注册", UvpColor.SuccessText,
            "心跳 ${state.config.keepaliveIntervalSeconds}s"
        )
        SipStateDto.Registering -> BannerSpec(
            UvpColor.WarningBg, UvpColor.WarningBorder, UvpColor.Warning,
            "正在注册…", UvpColor.Warning, "等待平台响应"
        )
        SipStateDto.Disconnected -> BannerSpec(
            UvpColor.BorderLight, UvpColor.Border, UvpColor.TextHint,
            "未连接", UvpColor.TextSecondary,
            if (state.config.isReadyToRegister) "编辑配置 → 注册"
            else "请先填写 SIP 配置"
        )
        SipStateDto.Failed -> {
            val reason = state.events.filterIsInstance<com.uvp.sim.ui.model.SimEventDto.RegistrationFailed>()
                .firstOrNull()?.reason ?: "未知原因"
            BannerSpec(
                UvpColor.DangerBg, UvpColor.DangerBorder, UvpColor.Danger,
                "注册失败", UvpColor.DangerText, reason
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(spec.bg, RoundedCornerShape(8.dp))
            .border(1.dp, spec.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(spec.dot))
        Spacer(Modifier.width(10.dp))
        Text(spec.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = spec.textColor)
        Spacer(Modifier.weight(1f))
        // 推流状态(原视频框右上角 chip 上移到此):仅注册后显示
        val streamLabel = when (state.sip) {
            SipStateDto.InCall -> "1280×720 · 25fps · LIVE"
            SipStateDto.Registered -> "1280×720 · 预览"
            else -> null
        }
        if (streamLabel != null) {
            Text(streamLabel, fontSize = 11.sp, color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.width(10.dp))
        }
        Text(spec.extra, fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace,
            maxLines = 1)
    }
}

private data class BannerSpec(
    val bg: Color, val border: Color, val dot: Color,
    val text: String, val textColor: Color, val extra: String
)
