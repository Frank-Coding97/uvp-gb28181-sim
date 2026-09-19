package com.uvp.sim.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
 * 主屏顶部状态 banner —— 状态 + 一键 CTA 合一。
 *
 * 设计:iOS HIG "状态即操作" 模式(参考微信 / AirPods / Find My)。
 * 未连接时右侧嵌"注册"按钮,用户一眼可点。
 * 注册中显示"取消",已注册显示"注销"。
 * 底部再没有独立的注册按钮块 —— 减少 44dp 高度让首屏能装更多内容。
 *
 * **重连优先于注册状态**(GB/T 28181 §5.2 长连接):连接被对端打死后注册状态会回到
 * Disconnected,只按 [AppUiState.sip] 渲染就会显示「未连接 · 配置已就绪」+「注册」按钮 ——
 * 而设备其实正在自己重连。那句话是错的,且会诱导操作员去点按钮、或者直接判定功能坏了。
 * 所以只要 [AppUiState.reconnect] 非空,横幅一律改报"正在第 N 次自动重连"。
 */
@Composable
internal fun StatusBanner(
    state: AppUiState,
    actions: AppActions? = null,
    onFeedback: (String) -> Unit = {},
    sipConfigEditing: Boolean = false,
) {
    val spec = when {
        state.reconnect != null -> {
            val attempt = state.reconnect
            BannerSpec(
                UvpColor.WarningBg, UvpColor.WarningBorder, UvpColor.Warning,
                "连接已断开 · 正在重连",
                UvpColor.Warning,
                "第 ${attempt.attempt} 次 · ${attempt.reason.label}",
            )
        }
        else -> when (state.sip) {
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
                if (state.config.isReadyToRegister) "配置已就绪"
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
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(spec.bg, RoundedCornerShape(10.dp))
            .border(1.dp, spec.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(spec.dot))
        Spacer(Modifier.width(8.dp))
        // 状态 + 附加信息合成一行,减少垂直高度
        Text(spec.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = spec.textColor)
        val streamLabel = when (state.sip) {
            SipStateDto.InCall -> "1280×720 · 25fps · LIVE"
            SipStateDto.Registered -> "1280×720 · 预览"
            else -> null
        }
        // 重连中不显示分辨率/预览这类"链路正常"的暗示 —— 它此刻不成立。
        val subText = if (state.reconnect != null) spec.extra else (streamLabel ?: spec.extra)
        if (subText.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                "· $subText",
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                fontFamily = if (streamLabel != null && state.reconnect == null) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
        // 右侧 CTA:根据 SIP 状态显示 注册 / 取消 / 注销
        if (actions != null) {
            StatusCta(
                state = state,
                actions = actions,
                onFeedback = onFeedback,
                sipConfigEditing = sipConfigEditing,
            )
        }
    }
}

@Composable
private fun StatusCta(
    state: AppUiState,
    actions: AppActions,
    onFeedback: (String) -> Unit,
    sipConfigEditing: Boolean,
) {
    when (state.sip) {
        SipStateDto.Disconnected, SipStateDto.Failed -> {
            val configReady = state.config.isReadyToRegister
            val ready = registrationButtonEnabled(configReady, sipConfigEditing)
            Button(
                onClick = {
                    actions.onConnect()
                    onFeedback("正在注册…")
                },
                enabled = ready,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UvpColor.Primary,
                    disabledContainerColor = UvpColor.Primary.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(
                    "注册",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
            }
        }
        SipStateDto.Registering -> {
            OutlinedButton(
                onClick = {
                    actions.onCancelConnect()
                    onFeedback("已取消注册")
                },
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, UvpColor.Warning.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Warning)
            ) {
                Text("取消", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        SipStateDto.Registered, SipStateDto.InCall -> {
            OutlinedButton(
                onClick = {
                    actions.onDisconnect()
                    onFeedback("已注销")
                },
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, UvpColor.Danger.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Danger)
            ) {
                Text("注销", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private data class BannerSpec(
    val bg: Color, val border: Color, val dot: Color,
    val text: String, val textColor: Color, val extra: String
)
