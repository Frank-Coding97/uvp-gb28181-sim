package com.uvp.sim.ui.capability.catalog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor

/** 「复位到默认?」确认 dialog,确认后调用 [onConfirm] 重置树。 */
@Composable
internal fun ResetTreeConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("复位到默认?") },
        text = {
            Text(
                "把当前编辑中的目录树替换为默认树(从设备配置生成的 3 节点扁平树)。" +
                "替换后还需要点保存才会真正写入平台。",
                color = UvpColor.TextSecondary, fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("复位", color = UvpColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = UvpColor.Primary)
            }
        }
    )
}

/** 「放弃未保存的修改?」返回前的确认 dialog。 */
@Composable
internal fun LeaveUnsavedConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("放弃未保存的修改?") },
        text = {
            Text("当前有未保存的目录树修改,返回后会丢失。",
                color = UvpColor.TextSecondary, fontSize = 13.sp)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("放弃修改", color = UvpColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("继续编辑", color = UvpColor.Primary)
            }
        }
    )
}
