package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.GbVersion
import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.sip.NatSituation
import com.uvp.sim.sip.RportObservation
import com.uvp.sim.ui.model.SipStateDto

/**
 * 设备配置 — 集中所有"设备级"参数,跟"通道""音视频"分开。
 *
 * 结构:
 *   [国标版本]   GbVersion 切换(影响 Catalog/DeviceInfo/DeviceStatus 输出形态)
 *   [基本信息]   设备名称 / 注册周期 / 心跳间隔 / 超时次数
 *   [出厂信息]   厂商 / 型号 / 固件 / 硬件版本(DeviceInfo §9.3.2 应答字段)
 *
 * 对讲传输方式留在 SIP 卡跟信令传输并排,便于一处编辑两个传输参数。
 *
 * 注册态全部 disabled,跟 SIP 卡和通道页保持一致(注销后再改)。
 */
@Composable
fun DeviceConfigScreen(state: AppUiState, actions: AppActions) {
    val toast = LocalToastHost.current
    val locked = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    val scroll = rememberScrollState()

    var gbVersion by remember(state.config) { mutableStateOf(state.config.gbVersion) }
    var name by remember(state.config) { mutableStateOf(state.config.device.name) }
    var expires by remember(state.config) {
        mutableStateOf(state.config.expiresSeconds.toString())
    }
    var keepalive by remember(state.config) {
        mutableStateOf(state.config.keepaliveIntervalSeconds.toString())
    }
    var maxTimeouts by remember(state.config) {
        mutableStateOf(state.config.maxKeepaliveTimeouts.toString())
    }
    var multiResponsePageSize by remember(state.config) {
        mutableStateOf(state.config.multiResponsePageSize.toString())
    }
    var manufacturer by remember(state.config) { mutableStateOf(state.config.device.manufacturer) }
    var model by remember(state.config) { mutableStateOf(state.config.device.model) }
    var firmware by remember(state.config) { mutableStateOf(state.config.device.firmware) }
    var hardwareVersion by remember(state.config) { mutableStateOf(state.config.device.hardwareVersion) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("国标版本")
        GroupCard {
            GbVersionSelector(
                selected = gbVersion,
                enabled = !locked,
                onSelect = { gbVersion = it }
            )
            NegotiatedVersionRow(
                declared = gbVersion,
                platform = state.platformGbVersion,
                registered = locked,
            )
            SignalingCharsetRow(
                declared = gbVersion,
                platform = state.platformGbVersion,
            )
            AddressTranslationRow(
                observation = state.rportObservation,
                usesTcp = state.config.transport.name.equals("TCP", ignoreCase = true),
            )
        }

        SectionLabel("基本信息")
        GroupCard {
            InlineEditableRow(
                label = "设备名称",
                value = name,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { name = it }
            InlineEditableRow(
                label = "注册周期",
                value = expires,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("秒", !locked) }
            ) { expires = it.filter { c -> c.isDigit() } }
            InlineEditableRow(
                label = "心跳间隔",
                value = keepalive,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("秒", !locked) }
            ) { keepalive = it.filter { c -> c.isDigit() } }
            InlineEditableRow(
                label = "超时次数",
                value = maxTimeouts,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("次", !locked) }
            ) { maxTimeouts = it.filter { c -> c.isDigit() } }
            InlineEditableRow(
                label = "多响应每包",
                value = multiResponsePageSize,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("条", !locked) }
            ) { multiResponsePageSize = it.filter { c -> c.isDigit() } }
        }

        SectionLabel("出厂信息 · DeviceInfo 应答字段")
        GroupCard {
            InlineEditableRow(
                label = "厂商",
                value = manufacturer,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { manufacturer = it }
            InlineEditableRow(
                label = "型号",
                value = model,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { model = it }
            InlineEditableRow(
                label = "固件版本",
                value = firmware,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { firmware = it }
            InlineEditableRow(
                label = "硬件版本",
                value = hardwareVersion,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { hardwareVersion = it }
        }

        Button(
            enabled = !locked,
            onClick = {
                if (name.isBlank()) {
                    toast.error("设备名称不能为空")
                    return@Button
                }
                if (manufacturer.isBlank() || model.isBlank()) {
                    toast.error("厂商 / 型号不能为空")
                    return@Button
                }
                actions.onConfigSave(
                    state.config.copy(
                        gbVersion = gbVersion,
                        device = state.config.device.copy(
                            name = name,
                            manufacturer = manufacturer,
                            model = model,
                            firmware = firmware,
                            hardwareVersion = hardwareVersion
                        ),
                        expiresSeconds = expires.toIntOrNull()?.coerceIn(60, 86_400) ?: 3600,
                        keepaliveIntervalSeconds = keepalive.toIntOrNull()?.coerceIn(15, 600) ?: 60,
                        maxKeepaliveTimeouts = maxTimeouts.toIntOrNull()?.coerceIn(1, 10) ?: 3,
                        multiResponsePageSize = multiResponsePageSize.toIntOrNull()?.coerceIn(1, 10_000) ?: 50,
                    )
                )
                toast.success("设备配置已保存")
            },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (locked) "注销后修改" else "保存",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) UvpColor.TextHint else Color.White
            )
        }

        Spacer(Modifier.height(2.dp))
        Text(
            "范围:注册周期 60–86400 秒;心跳间隔 15–600 秒;超时次数 1–10;多响应每包 1–10000 条",
            fontSize = 10.sp,
            color = UvpColor.TextHint,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextHint,
        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun GbVersionSelector(
    selected: GbVersion,
    enabled: Boolean,
    onSelect: (GbVersion) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GbVersion.entries.forEach { v ->
            val isSel = v == selected
            val bg = when {
                !enabled -> UvpColor.Border
                isSel -> UvpColor.Primary
                else -> UvpColor.Surface
            }
            val fg = when {
                !enabled -> UvpColor.TextHint
                isSel -> Color.White
                else -> UvpColor.Text
            }
            val border = if (isSel) UvpColor.Primary else UvpColor.Border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSelect(v) },
                contentAlignment = Alignment.Center
            ) {
                Text(v.label, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 附录 I 协商结果的只读展示。
 *
 * 为什么要露出来:出站报文形态取决于 min(本机声明, 平台声明) —— DeviceInfo 的
 * HardwareVersion、DeviceStatus 的嵌套 Alarmstatus、AlarmStatus 的 DutyStatus 都跟着它走。
 * 而「本机选了 2022,对面平台只声明 2.0」这件事在界面上原本完全不可见,联调时只能翻日志抓包。
 */
@Composable
private fun NegotiatedVersionRow(declared: GbVersion, platform: GbVersion?, registered: Boolean) {
    val (text, color) = when {
        platform != null -> {
            val effective = minOf(declared, platform)
            if (effective != declared) {
                "平台声明 ${platform.xGbVer} · 有效 ${effective.xGbVer}(出站已降级)" to UvpColor.Warning
            } else {
                "平台声明 ${platform.xGbVer} · 有效 ${effective.xGbVer}" to UvpColor.TextHint
            }
        }
        registered -> "平台未声明 X-GB-Ver,按本机 ${declared.xGbVer} 交互" to UvpColor.TextHint
        else -> "平台版本:注册后协商" to UvpColor.TextHint
    }
    Text(
        text,
        fontSize = 11.sp,
        // 显式给行高:只改 fontSize 会继承 bodyLarge 的 24sp 行高,父容器一收窄就裁字。
        lineHeight = 16.sp,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 信令字符集(GB/T 28181 §6.10)的**只读**展示。
 *
 * 为什么不给下拉:字符集不是设备厂商可自由选的参数 —— 2016 §6.10 是「**宜**采用 GB 2312」、
 * 2022 §6.10 是「**应**采用 GB 18030」,跟着上面选的国标版本走就是唯一正确解。给个下拉只会
 * 制造「两个实现方各理解一次」,而选错的表现只是一片中文乱码,没人能看出是自己选错的。
 *
 * 为什么要露出来:出站到底用什么编码,原来在界面上**完全不可见**,联调时只能翻日志抓包 ——
 * 「声明 GB2312 却发 UTF-8 字节」这种故障就是靠这一行一眼看出来的(理由同上面的 X-GB-Ver 协商行)。
 *
 * 取值口径与 [NegotiatedVersionRow] 一致:有效版本 = min(本机声明, 平台声明);平台未声明则不降级。
 */
@Composable
private fun SignalingCharsetRow(declared: GbVersion, platform: GbVersion?) {
    val effective = if (platform != null) minOf(declared, platform) else declared
    val outbound = SignalingCharset.of(effective)
    val strength = if (outbound == SignalingCharset.GB18030) "应" else "宜"
    Text(
        "信令字符集:出站 ${outbound.xmlLabel}(§6.10 $strength)· 入站按报文声明解码",
        fontSize = 11.sp,
        // 与上行同款:只改 fontSize 会继承 bodyLarge 的 24sp 行高,收窄时裁字。
        lineHeight = 16.sp,
        color = UvpColor.TextHint,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 平台视角的本机端点 —— 现场回答「平台到底能不能连上我」。
 *
 * 判定依据是注册响应 Via 里回填的 `received` / `rport`(RFC 3581)。
 * ⛔ 这不是国标要求(GB/T 28181-2022 全文没有这两个参数,标准给 NAT 的答案是 TCP 长连接复用),
 * 所以**平台不回填是正常的**:那种情况只说"未回填",不当错误。真正要抓的是它确实回填、
 * 且与本机声明不一致时 —— 那说明中间存在地址转换,NAT 场景下必须用 TCP(§9.1.1 f)),
 * 否则故障表现为「注册成功,但点播/云台/查询全部超时」,与防火墙丢包长得一模一样。
 */
@Composable
private fun AddressTranslationRow(observation: RportObservation?, usesTcp: Boolean) {
    val (text, color) = when {
        // ⛔ UNKNOWN 必须与 null 合并处理:`assess` 在"平台没回填"时返回的**不是** null
        // (它仍会带上本机端点供展示),只判 null 会让这种情况落到下面的兜底分支,
        // 被说成"只回填了 IP 或端口之一" —— 与真实原因不符。
        observation == null || observation.situation == NatSituation.UNKNOWN ->
            "地址转换:平台未回填 Via 的 received/rport(不影响协议合规)" to UvpColor.TextHint

        observation.situation == NatSituation.DIRECT ->
            "地址转换:无 · 平台看到 ${observation.observedIp}:${observation.observedPort}" to
                UvpColor.TextHint

        observation.situation == NatSituation.NAT -> {
            val observed = "${observation.observedIp}:${observation.observedPort}"
            val local = "${observation.localIp}:${observation.localPort}"
            if (usesTcp) {
                "地址转换:有 · 平台看到 $observed(本机 $local)—— 已用 TCP 注册,符合 §9.1.1 f)" to
                    UvpColor.TextHint
            } else {
                "地址转换:有 · 平台看到 $observed(本机 $local)—— NAT 场景须改用 TCP 注册" to
                    UvpColor.Warning
            }
        }

        else -> "地址转换:无法判定(平台只回填了 IP 或端口之一)" to UvpColor.TextHint
    }
    Text(
        text,
        fontSize = 11.sp,
        // 与同卡片其它说明行一致:只改 fontSize 会继承 bodyLarge 的 24sp 行高,收窄时裁字。
        lineHeight = 16.sp,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun UnitSuffix(text: String, enabled: Boolean) {    Text(
        text,
        fontSize = 11.sp,
        color = if (enabled) UvpColor.TextHint else UvpColor.BorderLight,
        modifier = Modifier.padding(start = 6.dp)
    )
}
