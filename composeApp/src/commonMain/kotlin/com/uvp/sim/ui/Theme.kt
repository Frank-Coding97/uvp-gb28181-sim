package com.uvp.sim.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * F 方案 — 配套 UVP 平台企业蓝。
 * 颜色 token 来自 ~/code/uvp/apps/uvp-sim-prototype/index-v1.html :root,
 * 1:1 同步以确保 sim app 跟 UVP Web 平台视觉一致。
 */

object UvpColor {
    val Primary       = Color(0xFF1890FF)
    val PrimaryLight  = Color(0xFFE6F7FF)
    val PrimaryDark   = Color(0xFF096DD9)
    val Success       = Color(0xFF52C41A)
    val SuccessBg     = Color(0xFFF6FFED)
    val SuccessBorder = Color(0xFFB7EB8F)
    val SuccessText   = Color(0xFF389E0D)
    val Warning       = Color(0xFFFA8C16)
    val WarningBg     = Color(0xFFFFF7E6)
    val WarningBorder = Color(0xFFFFD591)
    val Danger        = Color(0xFFFF4D4F)
    val DangerBg      = Color(0xFFFFF1F0)
    val DangerBorder  = Color(0xFFFFA39E)
    val DangerText    = Color(0xFFCF1322)
    val Info          = Color(0xFF722ED1)
    val InfoBg        = Color(0xFFF9F0FF)
    val Text          = Color(0xFF1F2937)
    val TextSecondary = Color(0xFF6B7280)
    val TextHint      = Color(0xFF9CA3AF)
    val Border        = Color(0xFFE5E7EB)
    val BorderLight   = Color(0xFFF3F4F6)
    val Bg            = Color(0xFFF3F4F6)
    val Surface       = Color(0xFFFFFFFF)
    val CodeBg        = Color(0xFFFAFAFA)
}

private val UvpLightColors = lightColorScheme(
    primary = UvpColor.Primary,
    onPrimary = Color.White,
    primaryContainer = UvpColor.PrimaryLight,
    onPrimaryContainer = UvpColor.PrimaryDark,
    secondary = UvpColor.PrimaryDark,
    onSecondary = Color.White,
    surface = UvpColor.Surface,
    onSurface = UvpColor.Text,
    surfaceVariant = UvpColor.BorderLight,
    onSurfaceVariant = UvpColor.TextSecondary,
    background = UvpColor.Bg,
    onBackground = UvpColor.Text,
    outline = UvpColor.Border,
    outlineVariant = UvpColor.BorderLight,
    error = UvpColor.Danger,
    onError = Color.White
)

@Composable
fun UvpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UvpLightColors,
        content = content
    )
}

object UvpStatusColors {
    val Disconnected = UvpColor.TextHint
    val Registering = UvpColor.Warning
    val Registered = UvpColor.SuccessText
    val InCall = UvpColor.PrimaryDark
    val Failed = UvpColor.DangerText
}
