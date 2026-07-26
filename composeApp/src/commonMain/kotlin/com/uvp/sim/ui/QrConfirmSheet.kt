package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.QrProvisionPayload

/**
 * 确认页展示的数据(plan §5.5,评审 HIGH)。
 *
 * sim 无从判断一个 host 是否可信 —— 恶意二维码能把设备重配到攻击者的 SIP 平台。
 * 所以兑换成功后不直接落盘,先把**目标 host + 六元组摘要**摆给用户看,由人拍板。
 *
 * [passwordHint] 只带位数不带内容:确认页很可能被截图,密码明文一旦上屏就等于外泄。
 */
data class QrConfirmSummary(
    val host: String,
    val serverId: String,
    val domain: String,
    val endpoint: String,
    val transport: String,
    val passwordLength: Int,
    val passwordHint: String,
    /** 明文 HTTP 且 host 不是私网地址 —— 确认页要额外提示风险。 */
    val insecurePublicHost: Boolean,
)

fun buildQrConfirmSummary(baseUrl: String, payload: QrProvisionPayload): QrConfirmSummary =
    QrConfirmSummary(
        host = baseUrl,
        serverId = payload.serverId,
        domain = payload.domain,
        endpoint = "${payload.ip}:${payload.port}",
        transport = payload.transport.trim().uppercase(),
        passwordLength = payload.password.length,
        passwordHint = if (payload.password.isEmpty()) "未设置"
                       else "已设置(${payload.password.length} 位)",
        insecurePublicHost = isInsecurePublicHost(baseUrl),
    )

/**
 * 明文 HTTP + 非私网 host 判定。私网段(RFC1918 / loopback / link-local / `.local`)
 * 是本特性的正常场景,不提示;公网明文才值得警告。
 *
 * 判不准时按"是公网"处理(多提示一次胜过漏提示)。
 */
private fun isInsecurePublicHost(baseUrl: String): Boolean {
    if (!baseUrl.startsWith("http://", ignoreCase = true)) return false
    val host = baseUrl.removePrefix("http://").removePrefix("HTTP://")
        .substringBefore('/')
        .substringBefore(':')
        .lowercase()
    if (host == "localhost" || host.endsWith(".local")) return false
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { o -> o.isEmpty() || o.any { it !in '0'..'9' } }) {
        // 主机名形式无法判断归属,按公网处理
        return true
    }
    val a = octets[0].toIntOrNull() ?: return true
    val b = octets[1].toIntOrNull() ?: return true
    return when {
        a == 10 -> false
        a == 127 -> false
        a == 192 && b == 168 -> false
        a == 172 && b in 16..31 -> false
        a == 169 && b == 254 -> false
        else -> true
    }
}

/**
 * 兑换成功后的确认卡 —— "确认接入"才落盘,"取消"则 config 完全不变(用例 9.6/9.7)。
 */
@Composable
fun QrConfirmSheet(
    summary: QrConfirmSummary,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UvpColor.Surface)
            .padding(18.dp),
    ) {
        Text(
            "确认接入这个平台?",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.Text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "以下信息来自扫到的二维码。确认后会覆盖当前 SIP 配置(设备编码不变)。",
            fontSize = 12.sp,
            color = UvpColor.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        SummaryRow("目标平台", summary.host, emphasize = true)
        SummaryRow("平台编码", summary.serverId)
        SummaryRow("SIP 域", summary.domain)
        SummaryRow("平台地址", summary.endpoint)
        SummaryRow("传输协议", summary.transport)
        SummaryRow("SIP 密码", summary.passwordHint)

        if (summary.insecurePublicHost) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(UvpColor.WarningBg)
                    .border(1.dp, UvpColor.WarningBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "这个地址不在局域网内,且是明文 HTTP。若不是你自己的平台,请点取消。",
                    fontSize = 12.sp,
                    color = UvpColor.Warning,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel) {
                Text("取消", fontSize = 14.sp, color = UvpColor.TextSecondary)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary),
            ) {
                Text("确认接入", fontSize = 14.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = UvpColor.TextHint,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasize) UvpColor.Primary else UvpColor.Text,
            modifier = Modifier.weight(1f),
        )
    }
}
