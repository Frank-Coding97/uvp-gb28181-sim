package com.uvp.sim.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.compose.generated.resources.Res
import com.uvp.sim.compose.generated.resources.app_icon
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/**
 * 冷启动屏 - 4 层品牌页布局。
 *
 * 视觉分组:
 *   上组(品牌层, 位于屏幕上 1/3 附近):
 *     - Logo 96dp 圆角
 *     - 中文主标 "统一视频接入平台"
 *     - 英文全称 "UNIFIED VIDEO PLATFORM" (大写 + 字距)
 *   下组(功能层, 位于屏幕下 1/6):
 *     - Slogan "全能力手机国标 28181 模拟器"
 *
 * 时序:
 *   0        Logo + 上组文字一次性出现(与系统 splash 无缝衔接)
 *   80ms     slogan 淡入触发
 *   330ms    slogan 完成淡入 (250ms 动画)
 *   1530ms   驻留 1200ms 后触发 fade
 *   1830ms   fade 完成 (300ms), 主界面完全可交互
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        finishedListener = { if (!visible) onFinished() },
        label = "splash-overlay-fade",
    )
    // slogan 单独 fade-in,logo + 主标 首帧就位
    var sloganIn by remember { mutableStateOf(false) }
    val sloganAlpha by animateFloatAsState(
        targetValue = if (sloganIn) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "slogan-fade-in",
    )
    LaunchedEffect(Unit) {
        delay(80)
        sloganIn = true
        // 驻留 2000ms 让用户从容看完 4 层文字, 再 fade。总时长 2.33s。
        delay(2000)
        visible = false
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .alpha(overlayAlpha)
            // fade 期间吞掉所有点击,防止穿透触发主界面误操作
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // 上组: Logo + 中文主标 + 英文副标 - 屏幕上 1/3 位置(比 Center 上抬 15%)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.app_icon),
                contentDescription = "UVP",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "统一视频接入平台",
                color = UvpColor.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "UNIFIED VIDEO PLATFORM",
                color = UvpColor.TextHint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 3.sp,
            )
        }
        // 下组: slogan - 底部安全区上方 60dp, 分层清晰
        Text(
            text = "全能力手机国标 28181 模拟器",
            color = UvpColor.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .alpha(sloganAlpha),
        )
    }
}
