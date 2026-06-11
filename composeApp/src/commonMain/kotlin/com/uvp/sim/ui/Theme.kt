package com.uvp.sim.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Sim app theme — F. 配套 UVP 平台企业蓝 (#1890ff Ant Design).
 *
 * Light scheme is the default; dark variant kept minimal for now (M3 will
 * polish if老板 wants dark mode, see decisions/2026-06-10-visual-style.md).
 */
private val UvpPrimary = Color(0xFF1890FF)
private val UvpPrimaryDark = Color(0xFF096DD9)
private val UvpPrimaryLight = Color(0xFFE6F7FF)
private val UvpSurface = Color(0xFFFFFFFF)
private val UvpSurfaceVariant = Color(0xFFF3F4F6)
private val UvpOnSurface = Color(0xFF1F2937)
private val UvpOnSurfaceMuted = Color(0xFF6B7280)
private val UvpOutline = Color(0xFFE5E7EB)
private val UvpDanger = Color(0xFFFF4D4F)

private val UvpLightColors = lightColorScheme(
    primary = UvpPrimary,
    onPrimary = Color.White,
    primaryContainer = UvpPrimaryLight,
    onPrimaryContainer = UvpPrimaryDark,
    secondary = UvpPrimaryDark,
    onSecondary = Color.White,
    surface = UvpSurface,
    onSurface = UvpOnSurface,
    surfaceVariant = UvpSurfaceVariant,
    onSurfaceVariant = UvpOnSurfaceMuted,
    background = UvpSurfaceVariant,
    onBackground = UvpOnSurface,
    outline = UvpOutline,
    error = UvpDanger,
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
    val Disconnected = Color(0xFF9E9E9E)
    val Registering = Color(0xFFFFA000)
    val Registered = Color(0xFF2E7D32)
    val InCall = Color(0xFF1565C0)
    val Failed = Color(0xFFC62828)
}
