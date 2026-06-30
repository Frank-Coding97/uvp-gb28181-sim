package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.domain.transportErrorOf
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.sip.SdpOffer
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipRequest

/**
 * 通道分类纯函数 — 主类 + AcceptHandler 共用。返回 null = 可接受,非 null = (statusCode, reason) 拒绝。
 *
 * R4 #5:消除主类 classifyInviteTargetInline / AcceptHandler.classifyInviteTarget 重复实现,
 * 避免漂移风险。catalogTree 由调用者读取,保持 pure。
 */
internal fun classifyInviteTarget(channelId: String, catalogTree: List<CatalogNode>): Pair<Int, String>? {
    if (channelId.isBlank()) return null
    val node = catalogTree.firstOrNull { it.id == channelId }
        ?: return 404 to "Channel Not Found"
    return when (node.type) {
        CatalogNodeType.VideoChannel -> null
        CatalogNodeType.AlarmChannel -> 488 to "Not Acceptable Here (alarm channel does not stream)"
        CatalogNodeType.Device -> 488 to "Not Acceptable Here (cannot invite device root)"
        CatalogNodeType.BusinessGroup -> 488 to "Not Acceptable Here (cannot invite business group)"
        CatalogNodeType.VirtualOrg -> 488 to "Not Acceptable Here (cannot invite virtual org)"
    }
}

/** 从 INVITE 请求 URI 提取 channel ID(GB28181 §20.4 / §C.2.3)。 */
internal fun extractInviteTarget(invite: SipRequest): String {
    val uri = invite.requestUri ?: return ""
    val atIdx = uri.indexOf('@')
    val schemeIdx = uri.indexOf(':')
    return if (schemeIdx >= 0 && atIdx > schemeIdx) {
        uri.substring(schemeIdx + 1, atIdx)
    } else ""
}

/**
 * cross-review R3 拆分 — INVITE 接受路径:URI 解析 / 通道分类 / 拒绝构建 / SDP 解析 /
 * 200 OK 构建+发送。
 *
 * 设计原则(spec §4 / plan §2.1):
 * - 不写 SipState / activeStream(那是主类的职责),返回 [AcceptResult] 让主类决策
 * - 失败路径已经发对应 4xx/5xx(R2 #4 protocol compliance),Rejected/Failed 返回时
 *   主类只需"不切 InCall + 不赋 activeStream"
 * - 不持有 var 共享 state,通过 [InviteSharedState] 读 config/outbox/catalogTree/localIp
 */
internal class InviteAcceptHandler(
    private val shared: InviteSharedState,
    private val rtpSenderFactory: (host: String, port: Int, mode: RtpMode, expectedClientHost: String?) -> RtpSender,
    private val cseqProvider: () -> Int,
    private val localPortProvider: () -> Int,
) {

    /**
     * 给主类用的 INVITE 解析:URI 解析 + 通道分类。
     * 返回 null = 可接受,非 null = (statusCode, reason) 拒绝。
     *
     * R4 #5:转发到 top-level 纯函数,主类亦可直接调 top-level。保留 instance API 以
     * 兼容旧调用方;新代码直接调 top-level [classifyInviteTarget]。
     */
    fun classifyInviteTargetForCurrentTree(channelId: String): Pair<Int, String>? =
        classifyInviteTarget(channelId, shared.catalogTree.value)

    /**
     * 接受主流程。**主类必须先做 acceptInFlight 守卫**(activeStream != null 或正在接受
     * 时拒绝并发第二路),本 handler 只处理:
     *
     * 1. cam.setFacing / 更新 _currentChannelName
     * 2. SDP parse(失败 → 488 + Rejected)
     * 3. rtp = factory(...) + bindLocalPort(失败 → 500 + Rejected)
     * 4. 构建 + 发送 200 OK(失败 → Failed,关 rtp)
     * 5. 装配 [AcceptedInvite] 数据载体给主类
     *
     * **不**切 SipState,**不**赋 activeStream,**不**启动 media(全部交主类按 result 处理)。
     */
    suspend fun handleInvite(
        envelope: SipEnvelope,
        invite: SipRequest,
        channelId: String,
        cameraCapture: com.uvp.sim.camera.CameraCapture,
        currentChannelNameSink: (String) -> Unit,
    ): AcceptResult {
        cameraCapture.setFacing(shared.config.device.facingForChannel(channelId))
        currentChannelNameSink(shared.config.device.channelNameForChannel(channelId))

        // 1. SDP parse(失败 488)
        val offer = try {
            com.uvp.sim.sip.SdpParser.parseOffer(invite.body)
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("SDP parse", e))
            runCatching {
                shared.outbox.send(SipBuilders.buildSimpleError(invite, 488, "Not Acceptable Here"))
            }
            return AcceptResult.Rejected(488, "Not Acceptable Here (SDP parse)")
        }

        // SSRC 生成
        val ssrc = offer.ssrc ?: com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = shared.config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseqProvider() + 1) and 0x0FFF,
        )

        val rtpMode = when (offer.transport) {
            com.uvp.sim.sip.SdpTransport.UDP -> RtpMode.UDP
            com.uvp.sim.sip.SdpTransport.TCP -> when (offer.tcpSetup) {
                com.uvp.sim.sip.SdpTcpSetup.PASSIVE -> RtpMode.TCP_ACTIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTIVE -> RtpMode.TCP_PASSIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTPASS -> RtpMode.TCP_ACTIVE
            }
        }
        // P1-5: TCP_PASSIVE 模式下 expectedClientHost = SDP remote IP
        val expectedClientHost = if (rtpMode == RtpMode.TCP_PASSIVE) offer.remoteIp else null

        // 2. RTP bind(失败 500)
        val rtp = rtpSenderFactory(offer.remoteIp, offer.remotePort, rtpMode, expectedClientHost)
        val localRtpPort = try {
            rtp.bindLocalPort()
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("RTP bind", e))
            runCatching {
                shared.outbox.send(SipBuilders.buildSimpleError(invite, 500, "Server Internal Error"))
            }
            return AcceptResult.Rejected(500, "Server Internal Error (RTP bind)")
        }

        // 3. 构建 + 发送 200 OK
        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = shared.config.device.deviceId,
            localIp = shared.localIp,
            localRtpPort = localRtpPort,
            ssrc = ssrc,
            sessionName = "Play",
            transport = offer.transport,
            tcpSetup = offer.tcpSetup,
            mediaSpec = SipHeaderHelpers.buildSdpMediaSpec(shared.config),
        )
        val deviceContact = "<sip:${shared.config.device.deviceId}@${shared.localIp}:${localPortProvider()}>"
        val localToTag = SipBuilders.randomTag()
        val inviteFromUser = SipHeaderHelpers.parseUriUser(
            SipHeaderHelpers.parseUri(invite.fromHeader() ?: ""),
            fallback = shared.config.server.serverId,
        )
        val response = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = localToTag,
            sdpBody = sdpAnswer,
            userAgent = shared.config.userAgent,
            subject = SipBuilders.subject(
                // R2 #7:Subject sender ID 应为通道编码(GB §20.4)
                senderId = channelId.ifBlank { shared.config.device.deviceId },
                ssrc = ssrc,
                receiverId = inviteFromUser,
            ),
        )
        val inviteFromHeader = invite.fromHeader() ?: ""
        val inviteToHeader = invite.toHeader() ?: ""
        val inviteContact = invite.firstHeader(SipHeader.CONTACT) ?: ""
        val remoteUri = SipHeaderHelpers.parseUri(inviteFromHeader)
        val remoteTag = SipHeaderHelpers.parseTag(inviteFromHeader)
        val localUri = SipHeaderHelpers.parseUri(inviteToHeader)
        val remoteTarget = SipHeaderHelpers.parseUri(inviteContact).ifEmpty { remoteUri }

        try {
            shared.outbox.send(response).getOrThrow()
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("send 200 OK", e))
            try { rtp.close() } catch (_: Throwable) {}
            return AcceptResult.Failed(e)
        }

        // 4. 装配 AcceptedInvite 给主类
        return AcceptResult.Success(
            AcceptedInvite(
                cid = invite.callId() ?: "",
                rtp = rtp,
                offer = offer,
                ssrc = ssrc,
                cam = cameraCapture,
                channelId = channelId,
                localUri = localUri,
                localTag = localToTag,
                remoteUri = remoteUri,
                remoteTag = remoteTag,
                remoteTarget = remoteTarget,
                remoteSourceIp = envelope.sourceIp,
            )
        )
    }

    /**
     * 拒绝响应封装(486 Busy / 404 Not Found / 488 Not Acceptable 等)。
     * runCatching 兜底失败,不抛给主类(平台拿不到响应已是次要问题)。
     */
    suspend fun sendRejection(req: SipRequest, statusCode: Int, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req,
                statusCode = statusCode,
                reasonPhrase = reason,
                toTag = SipBuilders.randomTag(),
                userAgent = shared.config.userAgent,
            )
            shared.outbox.send(resp).getOrThrow()
        }
    }
}

/**
 * INVITE 接受成功后的数据载体 — 主类用这些字段装配 [InviteCoordinatorImpl.ActiveStream] + 启动 media。
 */
internal data class AcceptedInvite(
    val cid: String,
    val rtp: RtpSender,
    val offer: SdpOffer,
    val ssrc: String,
    val cam: com.uvp.sim.camera.CameraCapture,
    val channelId: String,
    val localUri: String,
    val localTag: String,
    val remoteUri: String,
    val remoteTag: String,
    val remoteTarget: String,
    val remoteSourceIp: String?,
)
