package com.uvp.sim.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 全局唯一的开关（Switch）配色。
 *
 * ## 为什么不能用 Material 默认值
 *
 * M3 关闭态是「轨道 = surfaceVariant、滑块 = outline」。本仓的两个 token 分别是
 * `#F3F4F6` 和 `#E5E7EB` —— 在白卡片上，近白轨道 + 浅灰滑块几乎糊成一片，
 * 看起来既不像开关也不像禁用，像是渲染没画完。
 *
 * 关闭态改成「中灰轨道 `UvpColor.BorderStrong` + 纯白滑块」：
 *  - 轨道与白卡片有明确分界，一眼能看出「这里有个能拨的东西」；
 *  - 白滑块压在灰轨道上，位置（左/右）和开关状态都能读出来；
 *  - 中间灰不抢注意力，不会跟旁边的蓝色主按钮打架。
 *
 * 电纸书（onyx 这类）尤其依赖这条：它的灰阶压缩得厉害，近白对近白的差别直接抹平。
 *
 * ## 用哪个
 *
 * 默认直接用 [UvpSwitch]，它已经绑好这套配色；只有需要局部覆盖时才自己传 `colors`。
 * ⛔ 不要再各处手写 `SwitchDefaults.colors(...)` 或裸调 `Switch(...)` —— 那正是
 * 本次统一之前 5 处开关出现 3 种样式的原因。
 */
@Composable
fun uvpSwitchColors(): SwitchColors = SwitchDefaults.colors(
    // ---- 开 ----
    checkedThumbColor = Color.White,
    checkedTrackColor = UvpColor.Primary,
    // 边框跟轨道同色：M3 未选中时会用边框描边，不同色会看到一圈奇怪的深浅边
    checkedBorderColor = UvpColor.Primary,
    checkedIconColor = Color.White,
    // ---- 关 ----
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = UvpColor.BorderStrong,
    uncheckedBorderColor = UvpColor.BorderStrong,
    uncheckedIconColor = Color.White,
    // ---- 禁用 ----
    // 整体压一档，但要保住「这是个开关、只是现在不能动」的形状：
    // 只降不透明度、不换成另一套灰，否则禁用态又会退回糊成一片。
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.85f),
    disabledCheckedTrackColor = UvpColor.Primary.copy(alpha = 0.38f),
    disabledCheckedBorderColor = Color.Transparent,
    disabledCheckedIconColor = Color.White.copy(alpha = 0.85f),
    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.85f),
    disabledUncheckedTrackColor = UvpColor.BorderStrong.copy(alpha = 0.40f),
    disabledUncheckedBorderColor = Color.Transparent,
    disabledUncheckedIconColor = Color.White.copy(alpha = 0.85f),
)

/**
 * 应用统一的开关控件。参数顺序与 Material3 [Switch] 对齐，行为完全一致，
 * 只是把 [uvpSwitchColors] 设为默认配色，避免各处漏配。
 */
@Composable
fun UvpSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = uvpSwitchColors(),
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
    )
}
