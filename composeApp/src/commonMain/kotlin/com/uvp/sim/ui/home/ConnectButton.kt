package com.uvp.sim.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.SipStateDto

/**
 * 主屏底部 注册 / 注册中 / 注销 三态按钮。`onFeedback` 用于 toast 提示
 * (实际 toast host 在 HomeScreen 注入)。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun ConnectButton(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    when (state.sip) {
        SipStateDto.Disconnected, SipStateDto.Failed -> {
            val ready = state.config.isReadyToRegister
            Button(
                onClick = {
                    actions.onConnect()
                    onFeedback("正在注册…")
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UvpColor.Primary,
                    disabledContainerColor = UvpColor.Primary.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(
                    if (ready) "注 册" else "请先填写 SIP 配置",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = if (ready) 4.sp else 1.sp
                )
            }
        }
        SipStateDto.Registering -> {
            OutlinedButton(
                onClick = {
                    actions.onCancelConnect()
                    onFeedback("已取消注册")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UvpColor.Primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Primary)
            ) {
                Text("注册中… 点击取消", fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
        }
        SipStateDto.Registered, SipStateDto.InCall -> {
            OutlinedButton(
                onClick = {
                    actions.onDisconnect()
                    onFeedback("已注销")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UvpColor.Danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Danger)
            ) {
                Text("注 销", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = UvpColor.Danger, letterSpacing = 4.sp)
            }
        }
    }
}
