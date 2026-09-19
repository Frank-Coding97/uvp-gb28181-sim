package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.FrontOsdConfig
import com.uvp.sim.gb28181.FrontOsdState
import com.uvp.sim.gb28181.OsdTextItem

/**
 * 「GB/T 28181 前端 OSD」配置卡片（A.2.1.12 `OSDCfgType`）。
 *
 * ## 为什么这一项**可以**在设备本地配（而遮挡/翻转那些不行）
 * 真机上「前端 OSD」本来就是**一份配置、两个入口**：摄像头自己的界面（对应上面那张
 * [OsdConfigCard] 的三层）与平台下发的 `OSDConfig`。改哪边，另一边刷新就能看到。
 * 所以这里给的就是国标那个面的编辑入口 —— 保存后既影响回读给平台的值，也影响界面回显。
 *
 * ## 与上面那张三层卡片的关系
 * - 本卡片改的是**协议字段**（绝对像素坐标、1~8 条自由文本）；
 * - 上面那张改的是**本机预览叠加**（5 个锚点、固定 3 层）。
 * - 两者在保存时互相换算：见 `FrontOsdConfig.defaultFor`（本机 → 国标）与
 *   `FrontOsdConfig.toLocalOsd`（国标 → 本机，**有损**）。
 * ⛔ 别指望两边字段一一对应 —— 国标没有字号/颜色，本机没有多条目。
 *
 * [fromPlatform] 只影响标题栏的措辞（"平台已下发" / "出厂派生"），不改变可编辑性。
 */
@Composable
fun FrontOsdCard(
    state: FrontOsdState,
    fromPlatform: Boolean,
    enabled: Boolean,
    onChange: (FrontOsdState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "GB/T 28181 前端 OSD",
                fontSize = 12.sp,
                color = UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (fromPlatform) "平台已下发" else "出厂派生",
                fontSize = 10.sp,
                color = if (fromPlatform) UvpColor.Primary else UvpColor.TextHint
            )
        }

        // ---- 时间戳 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "显示时间",
                fontSize = 13.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(0.4f)
            )
            Switch(
                checked = state.timeEnable == 1,
                enabled = enabled,
                onCheckedChange = { onChange(state.copy(timeEnable = if (it) 1 else 0)) },
                colors = uvpSwitchColors()
            )
        }
        if (state.timeEnable == 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CompactNumberField("X", state.timeX, enabled) { onChange(state.copy(timeX = it)) }
                CompactNumberField("Y", state.timeY, enabled) { onChange(state.copy(timeY = it)) }
            }
            ChoiceRow(
                label = "时间格式",
                options = TIME_TYPE_OPTIONS,
                selected = state.timeType,
                enabled = enabled,
                onSelect = { onChange(state.copy(timeType = it)) }
            )
            Text(
                "0 = YYYY-MM-DD HH:MM:SS · 1 = YYYY年MM月DD日HH:MM:SS",
                fontSize = 10.sp,
                color = UvpColor.TextHint
            )
        }

        // ---- 文字行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "显示文字",
                fontSize = 13.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(0.4f)
            )
            Switch(
                checked = state.textEnable == 1,
                enabled = enabled,
                onCheckedChange = { onChange(state.copy(textEnable = if (it) 1 else 0)) },
                colors = uvpSwitchColors()
            )
        }
        if (state.textEnable == 1) {
            for ((index, item) in state.items.withIndex()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = item.text,
                        onValueChange = { newText ->
                            onChange(state.copy(items = state.items.replacing(index) {
                                it.copy(text = newText.take(FrontOsdConfig.MAX_TEXT_LENGTH))
                            }))
                        },
                        enabled = enabled,
                        singleLine = true,
                        placeholder = {
                            Text("第 ${index + 1} 行", fontSize = 12.sp, color = UvpColor.TextHint)
                        },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = UvpColor.Primary,
                            unfocusedBorderColor = UvpColor.Border
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    CompactNumberField("X", item.x, enabled) {
                        onChange(state.copy(items = state.items.replacing(index) { row -> row.copy(x = it) }))
                    }
                    CompactNumberField("Y", item.y, enabled) {
                        onChange(state.copy(items = state.items.replacing(index) { row -> row.copy(y = it) }))
                    }
                    TextButton(
                        enabled = enabled,
                        onClick = { onChange(state.copy(items = state.items.filterIndexed { i, _ -> i != index })) }
                    ) {
                        Text("删", fontSize = 11.sp, color = DeleteAccent)
                    }
                }
            }
            if (state.items.size < FrontOsdConfig.MAX_ITEMS) {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        onChange(
                            state.copy(
                                // 新行落在上一行下方 24px 处（时间戳/文字行的默认行距），
                                // 就是给个"不重叠的起点"，用户可以再改坐标。
                                items = state.items + OsdTextItem(
                                    text = "",
                                    x = FrontOsdConfig.DEFAULT_TEXT_X,
                                    y = FrontOsdConfig.DEFAULT_TEXT_Y + 24 * state.items.size,
                                )
                            )
                        )
                    }
                ) {
                    Text("+ 添加一行", fontSize = 12.sp, color = UvpColor.Primary)
                }
            } else {
                Text(
                    "已达标准上限 ${FrontOsdConfig.MAX_ITEMS} 行",
                    fontSize = 10.sp,
                    color = UvpColor.TextHint
                )
            }
        }

        Text(
            "配置窗口 ${state.length}×${state.width}（协议坐标系，随当前分辨率）· " +
                "文字 ${state.sumNum}/${FrontOsdConfig.MAX_ITEMS} 行",
            fontSize = 10.sp,
            color = UvpColor.TextHint
        )
    }
}

/** `TimeType` 的三个取值：`null` = 不指定格式（标准没给它 default，缺席是一个真实状态）。 */
private val TIME_TYPE_OPTIONS: List<Pair<Int?, String>> = listOf(
    null to "不指定",
    0 to "0",
    1 to "1",
)

/** 「删除」按钮的强调色。⛔ 别用主题里的语义色做破坏性操作的颜色。 */
private val DeleteAccent = Color(0xFFE24B4A)

/** 三张卡片共用的 Switch 配色（与 [OsdConfigCard] 保持一致）。 */
@Composable
private fun uvpSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = UvpColor.Primary,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = UvpColor.Border
)

/** 原地替换第 [index] 项，返回新列表（[List] 不可变，不能直接赋值）。 */
private inline fun <T> List<T>.replacing(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, value -> if (i == index) transform(value) else value }

/**
 * 紧凑数字输入（左侧小标签 + 固定宽输入框）。
 *
 * ⛔ 输入时**过滤非数字**而不是"解析失败就不更新"：后者在用户退格清空时会让框里
 * 一直留着旧值，看起来像"删不掉"。
 */
@Composable
private fun CompactNumberField(
    label: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 10.sp, color = UvpColor.TextHint)
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(5)
                onChange(if (digits.isEmpty()) 0 else digits.toInt())
            },
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = UvpColor.Primary,
                unfocusedBorderColor = UvpColor.Border
            ),
            modifier = Modifier.width(66.dp)
        )
    }
}

/** 单选小胶囊（用于 `TimeType` 这种 2~3 个值的枚举）。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<Int?, String>>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = UvpColor.TextSecondary)
        for ((value, text) in options) {
            val active = value == selected
            Text(
                text,
                fontSize = 11.sp,
                color = if (active) Color.White else UvpColor.TextSecondary,
                modifier = Modifier
                    .background(
                        if (active) UvpColor.Primary else UvpColor.Bg,
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        1.dp,
                        if (active) UvpColor.Primary else UvpColor.Border,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}
