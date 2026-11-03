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
    // 字符集单选态:null 覆盖 → 版本默认(2016→GB2312 / 2022→GB18030),否则用存储的覆盖
    var charsetSel by remember(state.config) {
        mutableStateOf(
            state.config.signalingCharsetOverride ?: SignalingCharset.of(state.config.gbVersion)
        )
    }
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
                onSelect = { v ->
                    gbVersion = v
                    // 切版本后原选中项可能被禁用(2016↔2022 各自只认自己的国标编码)→ 回落版本默认
                    if (!charsetSel.isAvailableFor(v)) charsetSel = SignalingCharset.of(v)
                }
            )
            NegotiatedVersionRow(
                declared = gbVersion,
                platform = state.platformGbVersion,
                registered = locked,
            )
            SignalingCharsetSelector(
                selected = charsetSel,
                version = gbVersion,
                enabled = !locked,
                onSelect = { charsetSel = it },
            )
            SignalingCharsetRow(
                declared = gbVersion,
                platform = state.platformGbVersion,
                override = charsetSel.takeIf { it != SignalingCharset.of(gbVersion) },
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
                        // 与版本默认相同 → 存 null(跟版本走),否则存显式覆盖
                        signalingCharsetOverride = charsetSel.takeIf { it != SignalingCharset.of(gbVersion) },
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
 * 信令字符集(GB/T 28181 §6.10)单选 —— 三个选项平铺,按当前国标版本禁用不可选项:
 *   - 2016:GB2312(默认)· GB18030(禁用)· UTF-8
 *   - 2022:GB2312(禁用)· GB18030(默认)· UTF-8
 *
 * 为什么现在给选(2026-11-03,原来是只读展示):存量厂商大量实际发 UTF-8,
 * "跟版本走"在真实联调里会逼用户改国标版本来换编码。选项按版本**禁用**而不是藏掉,
 * 是为了让"2016 没有 GB18030、2022 没有 GB2312"这层约束在界面上可见,而不是让用户猜。
 */
@Composable
private fun SignalingCharsetSelector(
    selected: SignalingCharset,
    version: GbVersion,
    enabled: Boolean,
    onSelect: (SignalingCharset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SignalingCharset.entries.forEach { cs ->
            val optionEnabled = enabled && cs.isAvailableFor(version)
            val isSel = cs == selected
            val bg = when {
                !optionEnabled -> UvpColor.Border
                isSel -> UvpColor.Primary
                else -> UvpColor.Surface
            }
            val fg = when {
                !optionEnabled -> UvpColor.TextHint
                isSel -> Color.White
                else -> UvpColor.Text
            }
            val border = if (isSel && optionEnabled) UvpColor.Primary else UvpColor.Border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .clickable(enabled = optionEnabled) { onSelect(cs) },
                contentAlignment = Alignment.Center
            ) {
                Text(cs.xmlLabel, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 实际出站字符集的只读回显(配合上面的单选)。
 *
 * 出站口径 = 有效版本(min(本机, 平台))叠加手动覆盖 —— 平台协商降级时这里能一眼看出
 * 「我选的编码被版本兜底换掉了」。入站恒按报文声明解码,不受本配置影响。
 */
@Composable
private fun SignalingCharsetRow(
    declared: GbVersion,
    platform: GbVersion?,
    override: SignalingCharset?,
) {
    val effective = if (platform != null) minOf(declared, platform) else declared
    val outbound = SignalingCharset.effective(effective, override)
    val strength = if (outbound == SignalingCharset.GB18030) "应" else "宜"
    val (text, color) = when {
        // 覆盖存的是国标编码但有效版本把它兜底换掉了(如 2022+GB18030 对上 2016 平台)
        override != null && outbound != override ->
            "出站 ${outbound.xmlLabel}(平台协商降级,所选 ${override.xmlLabel} 不适用)" to UvpColor.Warning
        override != null ->
            "出站 ${outbound.xmlLabel}(手动指定)· 入站按报文声明解码" to UvpColor.TextHint
        else ->
            "出站 ${outbound.xmlLabel}(§6.10 $strength)· 入站按报文声明解码" to UvpColor.TextHint
    }
    Text(
        text,
        fontSize = 11.sp,
        // 与上行同款:只改 fontSize 会继承 bodyLarge 的 24sp 行高,收窄时裁字。
        lineHeight = 16.sp,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 平台视角的本机端点 —— 现场回答「平台到底能不能连上我」。
 *
 * 判定依据是注册响应 Via 里回填的 `received` / `rport`(RFC 3581)。
 * 这不是国标注册必选项,平台不回填是正常的:那种情况只说"未回填",不当错误。真正要抓的是它确实回填、
 * 且与本机声明不一致时 —— 那说明中间存在地址转换。应用会先尝试把完整映射端点用于后续
 * Contact;若下行仍异常,再考虑 TCP 或端口映射。UDP 不因检测到 NAT 就被强制切换。
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
                "地址转换:有 · 平台看到 $observed(本机 $local)—— 当前使用 TCP 注册" to
                    UvpColor.TextHint
            } else {
                "地址转换:有 · 平台看到 $observed(本机 $local)—— 已尝试用该端点更新 Contact;UDP 可继续使用" to
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
