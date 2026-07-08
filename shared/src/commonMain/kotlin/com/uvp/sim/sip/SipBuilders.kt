package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * SIP 报文构造 façade(saga §3.5 拆分后保留外观 API,实现委派到 4 个分文件 object):
 *  - [SipHeaders] — Date/Subject/branch/tag/Call-ID
 *  - [SipRegisterBuilders] — REGISTER / Unregister / Keepalive / addAuthorization
 *  - [SipInviteBuilders] — INVITE 200 / outbound INVITE/ACK/BYE / MESSAGE
 *  - [SipResponseBuilders] — Simple 200/4xx/5xx / OPTIONS / SUBSCRIBE 200 / NOTIFY
 *
 * 175 处 caller 0 改动 — 老接口全部委派,新增 SIP 域写法可直接调对应分文件 object。
 */
object SipBuilders {

    // ---- 公共头 / 随机 ID(委派 SipHeaders) ----
    fun rfc1123Date(instant: Instant = Clock.System.now()): String = SipHeaders.rfc1123Date(instant)
    fun subject(senderId: String, ssrc: String, receiverId: String): String = SipHeaders.subject(senderId, ssrc, receiverId)
    fun randomBranch(): String = SipHeaders.randomBranch()
    fun randomTag(): String = SipHeaders.randomTag()
    fun randomCallId(localIp: String): String = SipHeaders.randomCallId(localIp)

    // ---- REGISTER 域(委派 SipRegisterBuilders) ----
    fun buildRegister(
        config: SimConfig, cseq: Int, callId: String, branch: String, fromTag: String, localIp: String, localPort: Int
    ): SipRequest = SipRegisterBuilders.buildRegister(config, cseq, callId, branch, fromTag, localIp, localPort)

    fun addAuthorization(register: SipRequest, authorizationHeader: String, newCseq: Int, newBranch: String): SipRequest =
        SipRegisterBuilders.addAuthorization(register, authorizationHeader, newCseq, newBranch)

    fun buildUnregister(
        config: SimConfig, cseq: Int, callId: String, branch: String, fromTag: String, localIp: String, localPort: Int
    ): SipRequest = SipRegisterBuilders.buildUnregister(config, cseq, callId, branch, fromTag, localIp, localPort)

    fun buildKeepalive(
        config: SimConfig, sn: Int, cseq: Int, callId: String, branch: String, fromTag: String, localIp: String, localPort: Int
    ): SipRequest = SipRegisterBuilders.buildKeepalive(config, sn, cseq, callId, branch, fromTag, localIp, localPort)

    // ---- INVITE 域(委派 SipInviteBuilders) ----
    fun buildInvite200WithSdp(
        invite: SipRequest, deviceContact: String, toTag: String, sdpBody: String,
        userAgent: String? = null, subject: String? = null
    ): SipResponse = SipInviteBuilders.buildInvite200WithSdp(invite, deviceContact, toTag, sdpBody, userAgent, subject)

    fun buildOutboundInvite(
        config: SimConfig, localId: String, platformUri: String, sourceId: String, deviceSsrc: String, sdpBody: String,
        localIp: String, localPort: Int, cseq: Int, callId: String, branch: String, fromTag: String
    ): SipRequest = SipInviteBuilders.buildOutboundInvite(
        config, localId, platformUri, sourceId, deviceSsrc, sdpBody, localIp, localPort, cseq, callId, branch, fromTag
    )

    fun buildOutboundAck(
        config: SimConfig, requestUri: String, callId: String, cseq: Int, branch: String,
        deviceUri: String, fromTag: String, platformUri: String, remoteTag: String, localIp: String, localPort: Int
    ): SipRequest = SipInviteBuilders.buildOutboundAck(
        config, requestUri, callId, cseq, branch, deviceUri, fromTag, platformUri, remoteTag, localIp, localPort
    )

    fun buildBye(
        config: SimConfig, callId: String, cseq: Int, branch: String,
        localUri: String, localTag: String, remoteUri: String, remoteTag: String, remoteTarget: String,
        localIp: String, localPort: Int
    ): SipRequest = SipInviteBuilders.buildBye(
        config, callId, cseq, branch, localUri, localTag, remoteUri, remoteTag, remoteTarget, localIp, localPort
    )

    fun buildMessage(
        config: SimConfig, cseq: Int, callId: String, branch: String, fromTag: String,
        localIp: String, localPort: Int, xmlBody: String
    ): SipRequest = SipInviteBuilders.buildMessage(config, cseq, callId, branch, fromTag, localIp, localPort, xmlBody)

    // ---- 响应 / NOTIFY 域(委派 SipResponseBuilders) ----
    fun buildSimple200(request: SipRequest, toTag: String? = null, userAgent: String? = null): SipResponse =
        SipResponseBuilders.buildSimple200(request, toTag, userAgent)

    fun buildOptionsResponse(request: SipRequest, allowedMethods: List<SipMethod>, userAgent: String? = null): SipResponse =
        SipResponseBuilders.buildOptionsResponse(request, allowedMethods, userAgent)

    fun buildSimpleResponse(
        request: SipRequest, statusCode: Int, reasonPhrase: String, toTag: String? = null, userAgent: String? = null
    ): SipResponse = SipResponseBuilders.buildSimpleResponse(request, statusCode, reasonPhrase, toTag, userAgent)

    fun buildSimpleError(request: SipRequest, statusCode: Int, reasonPhrase: String, toTag: String? = null): SipResponse =
        SipResponseBuilders.buildSimpleError(request, statusCode, reasonPhrase, toTag)

    fun buildSubscribe200(
        request: SipRequest, toTag: String, expires: Int, terminated: Boolean = false, userAgent: String? = null
    ): SipResponse = SipResponseBuilders.buildSubscribe200(request, toTag, expires, terminated, userAgent)

    fun buildNotify(
        subscriberUri: String, callId: String, fromTag: String, toTag: String, event: String, subscriptionState: String,
        cseq: Int, xmlBody: String, localIp: String, localPort: Int, transport: String = "UDP", userAgent: String? = null
    ): SipRequest = SipResponseBuilders.buildNotify(
        subscriberUri, callId, fromTag, toTag, event, subscriptionState, cseq, xmlBody, localIp, localPort, transport, userAgent
    )
}
