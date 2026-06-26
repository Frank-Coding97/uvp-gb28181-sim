package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 共享 inline 编辑控件 — 由 HomeScreen / DeviceConfigScreen / MediaScreen / ChannelScreen /
 * OsdConfigCard 等多屏复用。从 HomeScreen.kt 拆出,package 保留 `com.uvp.sim.ui` 避免
 * 调用方需要新加 import。
 */

@Composable
internal fun KvRow(key: String, value: String, divider: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(key, fontSize = 11.5.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 11.5.sp, color = UvpColor.Text, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
    }
}

@Composable
internal fun InlineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) UvpColor.Text else UvpColor.TextSecondary
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(UvpColor.Surface, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    if (enabled) UvpColor.Border else UvpColor.BorderLight,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                    inner()
                }
            }
        )
    }
}

@Composable
internal fun InlineSegmented(
    label: String,
    active: String,
    options: List<String> = listOf("UDP", "TCP"),
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.CodeBg, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    if (enabled) UvpColor.Border else UvpColor.BorderLight,
                    RoundedCornerShape(6.dp)
                )
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { t ->
                val sel = t == active
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                sel && enabled -> UvpColor.PrimaryLight
                                sel -> UvpColor.PrimaryLight.copy(alpha = 0.4f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            if (sel) 1.5.dp else 0.dp,
                            when {
                                !sel -> Color.Transparent
                                enabled -> UvpColor.Primary
                                else -> UvpColor.Primary.copy(alpha = 0.5f)
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = enabled) { onChange(t) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            sel && !enabled -> UvpColor.Primary.copy(alpha = 0.6f)
                            !enabled -> UvpColor.TextHint
                            sel -> UvpColor.Primary
                            else -> UvpColor.TextSecondary
                        }
                    )
                }
            }
        }
    }
}

// ============= In-place editable row (no outline, focus underline) =============

/**
 * 一行 KV 风格原位编辑控件。左 label,右可编辑文字。
 *
 * 视觉:label 左对齐 72dp 宽,value 右占剩余宽,数字字段等宽字体。
 * 不画外框,聚焦时下面亮 1.5dp 蓝线,失焦回归边线灰。
 *
 * trailing 可选:用于"服务器 + 端口同行"场景,主输入占大头,trailing 占小头。
 */
@Composable
internal fun InlineEditableRow(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    placeholder: String = "",
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.width(72.dp),
                fontSize = 11.5.sp,
                color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.5.sp,
                    fontFamily = if (keyboard == KeyboardType.Number) FontFamily.Monospace
                    else FontFamily.Default,
                    color = if (enabled) UvpColor.Text else UvpColor.TextHint
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                visualTransformation = if (masked && !revealed) PasswordVisualTransformation()
                else VisualTransformation.None,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 12.5.sp,
                                fontFamily = if (keyboard == KeyboardType.Number) FontFamily.Monospace
                                else FontFamily.Default,
                                color = UvpColor.TextHint.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (masked) {
                Icon(
                    imageVector = if (revealed) Icons.Outlined.VisibilityOff
                    else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "隐藏密码" else "显示密码",
                    tint = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .clickable(enabled = enabled) { revealed = !revealed }
                )
            }
            trailing?.invoke()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused && enabled) 1.dp else 0.5.dp)
                .background(if (focused && enabled) UvpColor.Primary else UvpColor.BorderLight)
        )
    }
}

/** 紧凑端口字段,跟服务器 IP 同行,固定窄宽. */
@Composable
internal fun InlineCompactPort(
    port: String,
    enabled: Boolean,
    placeholder: String = "",
    onChange: (String) -> Unit
) {
    Spacer(Modifier.width(8.dp))
    Text(":", fontSize = 13.sp,
        color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint)
    Spacer(Modifier.width(4.dp))
    BasicTextField(
        value = port,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) UvpColor.Text else UvpColor.TextHint
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (port.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = UvpColor.TextHint.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
                innerTextField()
            }
        },
        modifier = Modifier.width(54.dp)
    )
}

/** 行式 segmented:左 label,右选项 chip 行. */
@Composable
internal fun InlineSegmentedRow(
    label: String,
    active: String,
    options: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.width(72.dp),
                fontSize = 11.5.sp,
                color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { opt ->
                    val sel = opt == active
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    sel && enabled -> UvpColor.PrimaryLight
                                    sel -> UvpColor.PrimaryLight.copy(alpha = 0.4f)
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    !sel -> UvpColor.BorderLight
                                    enabled -> UvpColor.Primary
                                    else -> UvpColor.Primary.copy(alpha = 0.5f)
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .clickable(enabled = enabled) { onChange(opt) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opt,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                sel && !enabled -> UvpColor.Primary.copy(alpha = 0.6f)
                                !enabled -> UvpColor.TextHint
                                sel -> UvpColor.Primary
                                else -> UvpColor.TextSecondary
                            }
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(UvpColor.BorderLight))
    }
}
