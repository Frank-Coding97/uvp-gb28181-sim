package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize

/**
 * 三层 OSD 视频叠加配置卡片(行业 IPC 标准:时间戳 / 通道名 / 自定义水印)。
 *
 * 三层独立可控:
 * - 时间戳:开关 + 位置 + 字号(文本由运行期 OsdTickerSource 注入)
 * - 通道名:开关 + 文本 + 位置 + 字号
 * - 自定义水印:开关 + 文本 + 位置 + 字号
 *
 * [enabled]:推流锁定时整个卡片不可改(避免运行中切配置撞 GL pipeline)。
 *
 * 改字段触发 [onChange],父级合并到 SimConfig 后调 onConfigSave。
 */
@Composable
fun OsdConfigCard(
    osd: OsdConfig,
    enabled: Boolean,
    onChange: (OsdConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OSD 视频叠加",
                fontSize = 12.sp,
                color = UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "仅直播推流烧戳",
                fontSize = 10.sp,
                color = UvpColor.TextHint
            )
        }

        OsdLayerRow(
            label = "时间戳",
            layer = osd.timestamp,
            enabled = enabled,
            showText = false,
            onChange = { onChange(osd.copy(timestamp = it)) }
        )
        OsdLayerRow(
            label = "通道名",
            layer = osd.channelName,
            enabled = enabled,
            showText = true,
            textPlaceholder = "如:正门入口",
            onChange = { onChange(osd.copy(channelName = it)) }
        )
        OsdLayerRow(
            label = "自定义水印",
            layer = osd.watermark,
            enabled = enabled,
            showText = true,
            textPlaceholder = "如:@DEMO",
            onChange = { onChange(osd.copy(watermark = it)) }
        )
    }
}

@Composable
private fun OsdLayerRow(
    label: String,
    layer: OsdLayer,
    enabled: Boolean,
    showText: Boolean,
    textPlaceholder: String = "",
    onChange: (OsdLayer) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 13.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(0.4f)
            )
            Switch(
                checked = layer.enabled,
                enabled = enabled,
                onCheckedChange = { onChange(layer.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = UvpColor.Primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = UvpColor.Border
                )
            )
        }

        if (showText) {
            OutlinedTextField(
                value = layer.text,
                onValueChange = { onChange(layer.copy(text = it.take(32))) },
                enabled = enabled && layer.enabled,
                singleLine = true,
                placeholder = { Text(textPlaceholder, fontSize = 12.sp, color = UvpColor.TextHint) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = UvpColor.Primary,
                    unfocusedBorderColor = UvpColor.Border
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
        }

        InlineSegmented(
            label = "位置",
            active = layer.position.label,
            options = OsdPosition.entries.map { it.label },
            enabled = enabled && layer.enabled
        ) { picked ->
            val pos = OsdPosition.entries.firstOrNull { it.label == picked } ?: layer.position
            onChange(layer.copy(position = pos))
        }

        InlineSegmented(
            label = "字号",
            active = layer.size.label,
            options = OsdSize.entries.map { it.label },
            enabled = enabled && layer.enabled
        ) { picked ->
            val sz = OsdSize.entries.firstOrNull { it.label == picked } ?: layer.size
            onChange(layer.copy(size = sz))
        }
    }
}

private val OsdPosition.label: String
    get() = when (this) {
        OsdPosition.TOP_LEFT -> "左上"
        OsdPosition.TOP_RIGHT -> "右上"
        OsdPosition.BOTTOM_LEFT -> "左下"
        OsdPosition.BOTTOM_RIGHT -> "右下"
        OsdPosition.CENTER -> "居中"
    }

private val OsdSize.label: String
    get() = when (this) {
        OsdSize.SMALL -> "小"
        OsdSize.MEDIUM -> "中"
        OsdSize.LARGE -> "大"
    }
