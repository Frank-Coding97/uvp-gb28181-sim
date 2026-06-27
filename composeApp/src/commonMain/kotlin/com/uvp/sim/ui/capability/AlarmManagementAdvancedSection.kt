package com.uvp.sim.ui.capability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.SipStateDto

/**
 * 高级模拟折叠区 — MediaStatus 122/123 异常演示(M5 batch1 §7.9)。
 *
 * 拆出原因:AlarmManagementScreen 主流程聚焦报警单编辑,这块独立的协议触点
 * 留在主文件干扰阅读;封装成一个组件,主屏只需要 expanded state hoisting。
 *
 * GB §9.5.3 异常通知。触发后给注册中心 + Alarm 订阅人各发一条 MediaStatus NOTIFY。
 */
@Composable
internal fun AlarmAdvancedSimulationSection(
    sipState: SipStateDto,
    actions: AppActions,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val toast = LocalToastHost.current
    val canSimulate = sipState == SipStateDto.Registered
    CollapsibleHeader(
        icon = Icons.Outlined.Code,
        title = "高级模拟 (调试用)",
        count = 0,
        expanded = expanded,
        onToggle = onToggle
    )
    if (expanded) {
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "GB §9.5.3 异常通知。触发后给注册中心 + Alarm 订阅人各发一条 MediaStatus NOTIFY。",
                fontSize = 11.sp, color = UvpColor.TextSecondary
            )
            OutlinedButton(
                onClick = {
                    actions.onSimulateMediaStatusAbnormal(122)
                    toast.info("已发送 MediaStatus 122 (录像异常)")
                },
                enabled = canSimulate,
                modifier = Modifier.fillMaxWidth()
            ) { Text("模拟录像异常 (NotifyType=122)") }
            OutlinedButton(
                onClick = {
                    actions.onSimulateMediaStatusAbnormal(123)
                    toast.info("已发送 MediaStatus 123 (存储满)")
                },
                enabled = canSimulate,
                modifier = Modifier.fillMaxWidth()
            ) { Text("模拟存储满 (NotifyType=123)") }
            if (!canSimulate) {
                Text(
                    "未注册,按钮禁用",
                    fontSize = 10.sp, color = UvpColor.TextHint
                )
            }
        }
    }
}
