package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import com.uvp.sim.config.QrFetchResult
import com.uvp.sim.config.QrPayloadValidator
import com.uvp.sim.config.QrProvisionPayload
import com.uvp.sim.config.QrTokenParser
import com.uvp.sim.config.SimConfig
import com.uvp.sim.ui.model.SipStateDto
import kotlinx.coroutines.launch

/** 扫到的码不是本平台的接入码(plan §5.9)。 */
const val QR_NOT_UVP_CODE = "不是 UVP 平台的接入二维码"

private const val QR_STATE_BLOCKED = "请先断开连接再扫码配置"

/**
 * 扫码入口的准入判定(plan §5.2,用例 9.1-9.5)。
 *
 * 返回 null 表示可进;否则返回拦截提示文案。
 *
 * **只有 `Disconnected` 放行**,`Failed` 也拦:`AppEngine.updateConfig` 在
 * `engine != null` 时会 `disconnect(); connect()`,而 `Failed` 表示"曾连接后失败",
 * engine 可能仍存活 → 回填会触发意外重连。让用户先手动断开,比新增一条 shared
 * 公共 API 更简单,也不影响别的调用方。
 */
fun qrEntryBlockReason(sip: SipStateDto): String? =
    if (sip == SipStateDto.Disconnected) null else QR_STATE_BLOCKED

/** [QrFetchResult] → 用户可读文案(plan §5.9)。 */
fun qrErrorMessage(result: QrFetchResult): String = when (result) {
    is QrFetchResult.Success -> ""
    is QrFetchResult.Invalidated -> result.message.ifBlank { "二维码已失效,请在平台重新生成" }
    is QrFetchResult.Malformed -> "二维码内容已损坏"
    // 5xx 单独一类:归到"网络错误"会让用户去查手机 Wi-Fi,而真正该看的是平台日志。
    is QrFetchResult.ServerError -> "平台处理失败,请查看平台日志"
    is QrFetchResult.NetworkError -> "连不上平台,请检查手机与平台是否同网络"
}

/**
 * 六元组回填(plan §5.7)。一次性 copy,不做分步写入。
 *
 * `deviceId` / 通道编码不动 —— 那是本机设备身份,不该被平台二维码改写(spec §2.1)。
 * transport 解析不出时保留原值:静默改协议会让用户查半天注册失败(落盘前
 * [QrPayloadValidator] 已经拦住未知值,这里只是兜底)。
 */
fun applyQrPayload(config: SimConfig, payload: QrProvisionPayload): SimConfig = config.copy(
    server = config.server.copy(
        ip = payload.ip,
        port = payload.port,
        serverId = payload.serverId,
        domain = payload.domain,
    ),
    device = config.device.copy(password = payload.password),
    transport = QrPayloadValidator.parseTransport(payload.transport) ?: config.transport,
)

/** 扫码页内部阶段。 */
private sealed class QrScanStage {
    data object Scanning : QrScanStage()
    data object Exchanging : QrScanStage()
    data class Confirming(
        val summary: QrConfirmSummary,
        val payload: QrProvisionPayload,
    ) : QrScanStage()
}

/**
 * 全屏扫码页(plan §5.2)。
 *
 * 流程:取景 → 解码 → 解析 fragment token → POST 兑换 → **确认页** → 校验 → 落盘。
 *
 * 几条评审结论直接体现在这里,别改:
 * - 兑换成功先展示确认页,用户点"确认接入"才落盘;取消则 config 完全不变
 * - 确认后**再次检查 SIP 仍 Disconnected**(确认页停留期间用户可能手动连上了)
 * - 落盘前跑 [QrPayloadValidator.validate],不过则提示具体字段且不落盘
 * - HTTP 兑换走 [AppActions.onQrExchange],client 由宿主持有,不在 Composable 内创建
 */
@Composable
fun QrScanScreen(
    state: AppUiState,
    actions: AppActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toast = LocalToastHost.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<QrScanStage>(QrScanStage.Scanning) }
    // 解码成功后置位,防止 exchange 期间 / 确认页展示期间再吃新的扫码结果。
    var consumed by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = true, onBack = onClose)

    // 进页后状态若变化(例如其它入口触发了连接),直接退出,避免走到落盘阶段才发现。
    LaunchedEffect(state.sip) {
        if (state.sip != SipStateDto.Disconnected && stage is QrScanStage.Scanning) {
            toast.warning(QR_STATE_BLOCKED)
            onClose()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (stage is QrScanStage.Scanning || stage is QrScanStage.Exchanging) {
            ScanQrCode(
                modifier = Modifier.fillMaxSize(),
                onResult = { raw ->
                    if (consumed) return@ScanQrCode
                    val target = QrTokenParser.parseQrScan(raw)
                    if (target == null) {
                        // 非本平台码不置 consumed —— 让用户继续对准正确的码,不用退出重进。
                        toast.error(QR_NOT_UVP_CODE)
                        return@ScanQrCode
                    }
                    consumed = true
                    // 只对有效的 UVP 接入码提示一次,普通二维码和兑换失败都不响。
                    playQrScanSuccessSound()
                    stage = QrScanStage.Exchanging
                    scope.launch {
                        when (val result = actions.onQrExchange(target.baseUrl, target.token)) {
                            is QrFetchResult.Success -> {
                                stage = QrScanStage.Confirming(
                                    summary = buildQrConfirmSummary(target.baseUrl, result.payload),
                                    payload = result.payload,
                                )
                            }
                            else -> {
                                toast.error(qrErrorMessage(result))
                                onClose()
                            }
                        }
                    }
                },
                onError = { message ->
                    toast.error(message)
                    onClose()
                },
            )
            ScanOverlay(onClose = onClose, busy = stage is QrScanStage.Exchanging)
        }

        val confirming = stage as? QrScanStage.Confirming
        if (confirming != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                QrConfirmSheet(
                    summary = confirming.summary,
                    onCancel = {
                        // 取消 = config 完全不变(用例 9.7)。
                        onClose()
                    },
                    onConfirm = {
                        val payload = confirming.payload
                        // 确认页停留期间状态可能变了(用例 9.10)—— 再查一次才落盘。
                        if (state.sip != SipStateDto.Disconnected) {
                            toast.warning(QR_STATE_BLOCKED)
                            onClose()
                            return@QrConfirmSheet
                        }
                        val invalid = QrPayloadValidator.validate(payload)
                        if (invalid != null) {
                            toast.error("二维码参数不合规范:$invalid")
                            onClose()
                            return@QrConfirmSheet
                        }
                        actions.onConfigSave(applyQrPayload(state.config, payload))
                        toast.success("已回填 SIP 配置")
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanOverlay(onClose: () -> Unit, busy: Boolean) {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "qr-scan-line")
    val scanProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qr-scan-progress",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val frameSize = minOf(
            (maxWidth - 56.dp).coerceAtLeast(160.dp),
            maxHeight * 0.52f,
        )
        val frameTop = (maxHeight - frameSize) / 2f + 16.dp
        val frameSizePx = with(density) { frameSize.toPx() }
        val frameTopPx = with(density) { frameTop.toPx() }
        val accent = Color(0xFF00F52B)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameLeft = (size.width - frameSizePx) / 2f
            val frameBottom = frameTopPx + frameSizePx
            // Keep the camera feed clear inside the frame while dimming the rest.
            drawRect(Color.Black.copy(alpha = 0.56f), topLeft = Offset.Zero, size = Size(size.width, frameTopPx))
            drawRect(
                Color.Black.copy(alpha = 0.56f),
                topLeft = Offset(0f, frameBottom),
                size = Size(size.width, size.height - frameBottom),
            )
            drawRect(
                Color.Black.copy(alpha = 0.56f),
                topLeft = Offset(0f, frameTopPx),
                size = Size(frameLeft, frameSizePx),
            )
            drawRect(
                Color.Black.copy(alpha = 0.56f),
                topLeft = Offset(frameLeft + frameSizePx, frameTopPx),
                size = Size(size.width - frameLeft - frameSizePx, frameSizePx),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClose() }
                    .padding(6.dp),
            ) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Text(
            "将平台接入二维码放入框中",
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 70.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = frameTop)
                .size(frameSize),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val corner = with(density) { 28.dp.toPx() }
                val stroke = with(density) { 4.dp.toPx() }
                val inset = stroke / 2f
                val lineY = (size.height - stroke) * scanProgress
                val lineBrush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent, Color.Transparent),
                )

                drawRect(
                    Color.White.copy(alpha = 0.55f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = with(density) { 1.dp.toPx() },
                    ),
                )
                drawLine(accent, Offset(inset, inset), Offset(corner, inset), strokeWidth = stroke)
                drawLine(accent, Offset(inset, inset), Offset(inset, corner), strokeWidth = stroke)
                drawLine(accent, Offset(size.width - inset, inset), Offset(size.width - corner, inset), strokeWidth = stroke)
                drawLine(accent, Offset(size.width - inset, inset), Offset(size.width - inset, corner), strokeWidth = stroke)
                drawLine(accent, Offset(inset, size.height - inset), Offset(corner, size.height - inset), strokeWidth = stroke)
                drawLine(accent, Offset(inset, size.height - inset), Offset(inset, size.height - corner), strokeWidth = stroke)
                drawLine(accent, Offset(size.width - inset, size.height - inset), Offset(size.width - corner, size.height - inset), strokeWidth = stroke)
                drawLine(accent, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - corner), strokeWidth = stroke)
                if (!busy) {
                    drawRect(
                        brush = lineBrush,
                        topLeft = Offset(with(density) { 16.dp.toPx() }, lineY),
                        size = Size(size.width - with(density) { 32.dp.toPx() }, stroke),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = frameTop + frameSize + 28.dp)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("正在向平台兑换接入信息…", fontSize = 13.sp, color = Color.White)
                }
            } else {
                Text(
                    "保持二维码完整并置于框内",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}
