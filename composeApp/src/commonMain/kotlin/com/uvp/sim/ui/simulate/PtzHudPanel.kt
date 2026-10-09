package com.uvp.sim.ui.simulate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceCommandCategoryDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.simulate.ptz.PositionTabContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.uvp.sim.ui.simulate.ptz.HudTabRow
import com.uvp.sim.ui.simulate.ptz.ImageTabContent
import com.uvp.sim.ui.simulate.ptz.PtzTabContent
import com.uvp.sim.ui.simulate.ptz.StatusTabContent

/** 四类控制回显：云台操作、定位运行、图像配置、设备状态。 */
enum class HudTab(val title: String) {
    Ptz("云台"),
    Position("定位"),
    Image("图像"),
    Device("设备");

    companion object {
        /** 把 UI DTO 的语义分类映成 HudTab,null 表示不切. */
        fun fromCategory(category: DeviceCommandCategoryDto?): HudTab? = when (category) {
            DeviceCommandCategoryDto.Ptz -> Ptz
            DeviceCommandCategoryDto.Position -> Position
            DeviceCommandCategoryDto.Status -> Device
            DeviceCommandCategoryDto.Image -> Image
            DeviceCommandCategoryDto.Aux -> Device
            null -> null
        }
    }
}

@Composable
fun PtzHudPanel(
    state: DeviceControlDto,
    onLocalPtzAdjust: (Float, Float, Float) -> Unit,
    onLocalLensAdjust: (Float, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // 镜头速率积分属于面板生命周期，切到定位/设备页时仍持续执行。
    LaunchedEffect(state.focusSpeed, state.irisSpeed) {
        if (state.focusSpeed == 0f && state.irisSpeed == 0f) return@LaunchedEffect
        val focusRate = state.focusSpeed
        val irisRate = state.irisSpeed
        while (true) {
            delay(60L)
            onLocalLensAdjust(focusRate * 0.06f, irisRate * 0.06f)
        }
    }
    var selectedTab by remember { mutableStateOf(HudTab.Ptz) }

    // 各 Tab 是否有"未读"红点提示(收到命令但当前没在该 Tab)
    val tabBadges = remember { mutableStateOf<Set<HudTab>>(emptySet()) }

    // 平台命令到达 → 自动切到对应 Tab + 清掉该 Tab 的红点
    LaunchedEffect(state.lastCommand?.timestampMs) {
        val target = HudTab.fromCategory(state.lastCommandCategory) ?: return@LaunchedEffect
        if (target != selectedTab) {
            // 给其他非目标 Tab 留红点(不清掉),目标 Tab 切过去就消红点
            tabBadges.value = tabBadges.value + target
            selectedTab = target
        }
    }
    // 用户切到某 Tab → 清掉该 Tab 的红点
    LaunchedEffect(selectedTab) {
        tabBadges.value = tabBadges.value - selectedTab
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.BorderLight, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "平台控制 HUD",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Spacer(Modifier.weight(1f))
            // 当前 tab 提示
            Text(
                "平台触发自动切换",
                fontSize = 9.sp,
                color = UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        // Tab 行
        HudTabRow(
            selected = selectedTab,
            onSelect = { selectedTab = it },
            badges = tabBadges.value,
        )
        Spacer(Modifier.height(10.dp))
        // 统一视口保持模型和标签稳定；每页独立滚动，内容不再裁切。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(180))
                },
                label = "hud-tab-content"
            ) { tab ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (tab) {
                        HudTab.Ptz -> PtzTabContent(state, onLocalPtzAdjust, onLocalLensAdjust)
                        HudTab.Device -> StatusTabContent(state)
                        HudTab.Image -> ImageTabContent(state)
                        HudTab.Position -> PositionTabContent(state)
                    }
                }
            }
        }
    }
}
