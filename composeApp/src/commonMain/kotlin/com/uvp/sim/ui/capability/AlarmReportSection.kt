package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.AlarmReportConfig
import com.uvp.sim.gb28181.AlarmReportState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.UvpSwitch
import com.uvp.sim.ui.model.AlarmReportViewDto

/**
 * 报警子页的「上报开关」配置区（GB/T 28181-2022 A.2.1.18 `AlarmReport`）。
 *
 * ⭐ 这两个开关**不是装饰**：关掉之后对应类别的报警在 `ManscdpRouterImpl.reportAlarm`
 * 被直接拦下 —— 一条 MESSAGE、一条订阅 NOTIFY 都不会发。所以界面上必须说清
 * "关 = 设备根本没发"，而不是让用户以为"平台那边显示不出来"。
 *
 * 与同页「报警录像」的交互**刻意不同**：录像配置有 4 个字段、改完要一起提交，走草稿+保存；
 * 这里只有两个布尔开关，切换即生效（跟系统设置里的开关一致），避免"切了没点保存"的假状态。
 */
@Composable
internal fun AlarmReportSection(
    view: AlarmReportViewDto,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (AlarmReportState?) -> Unit,
) {
    val toast = LocalToastHost.current
    val enabledCount = (if (view.motionDetection) 1 else 0) + (if (view.fieldDetection) 1 else 0)

    CollapsibleHeader(
        icon = Icons.Outlined.NotificationsActive,
        title = "上报开关",
        count = enabledCount,
        expanded = expanded,
        onToggle = onToggle,
    )

    if (expanded) {
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                buildString {
                    append(if (view.configured) "已配置 · " else "出厂默认 · ")
                    append("移动侦测")
                    append(if (view.motionDetection) "开" else "关")
                    append(" / 区域入侵")
                    append(if (view.fieldDetection) "开" else "关")
                },
                fontSize = 11.sp,
                color = UvpColor.TextSecondary,
            )

            SwitchRow(
                title = "移动侦测上报",
                tag = "MotionDetection",
                checked = view.motionDetection,
                onChange = { on ->
                    onSave(currentState(view).copy(motionDetection = if (on) 1 else 0))
                    toast.info(if (on) "移动侦测上报已开启" else "移动侦测上报已关闭：该类报警不再发出")
                },
            )

            SwitchRow(
                title = "区域入侵上报",
                tag = "FieldDetection",
                checked = view.fieldDetection,
                onChange = { on ->
                    onSave(currentState(view).copy(fieldDetection = if (on) 1 else 0))
                    toast.info(if (on) "区域入侵上报已开启" else "区域入侵上报已关闭：该类报警不再发出")
                },
            )

            Text(
                "关闭 = 设备根本不发。人工触发、视频丢失这类通用报警不受这两个开关约束。",
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                lineHeight = 14.sp,
            )
            Text(
                "⚠️ 本机没有视频分析能力：这两类报警由报警页按模板模拟触发，不做真实侦测。",
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                lineHeight = 14.sp,
            )

            OutlinedButton(
                onClick = {
                    onSave(null)
                    toast.info("已恢复出厂默认（移动侦测开 / 区域入侵关）")
                },
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("恢复出厂", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    tag: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
            Text(tag, fontSize = 10.sp, color = UvpColor.TextHint)
        }
        UvpSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 当前生效值 → 协议状态。
 *
 * ⚠️ 两个字段都是**必选**，即使只改一个也要把另一个的原值一起写进去 ——
 * 否则回读会与生效值不一致（缺字段的解析是整块拒绝的）。
 */
private fun currentState(view: AlarmReportViewDto) = AlarmReportState(
    motionDetection = if (view.motionDetection) AlarmReportConfig.ON else AlarmReportConfig.OFF,
    fieldDetection = if (view.fieldDetection) AlarmReportConfig.ON else AlarmReportConfig.OFF,
)
