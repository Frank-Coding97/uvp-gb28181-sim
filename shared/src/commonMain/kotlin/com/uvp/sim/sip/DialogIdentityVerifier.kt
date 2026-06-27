package com.uvp.sim.sip

import com.uvp.sim.network.SipEnvelope

/**
 * 对话 dialog identity 校验 helper(Wave 7B P1-4,codex 第二轮 audit §4)。
 *
 * 引用 codex 原文:
 *   "Playback INFO 校验仅看 Call-ID,缺 fromTag/toTag/remoteUri 校验"
 *
 * GB28181 / RFC 3261 § 12 dialog identity 标准三元组:`Call-ID + local tag + remote tag`。
 * 仅校验 Call-ID 不够:Call-ID 通常在 SIP 头 plain text 里,LAN 抓包就能拿到,
 * 攻击者可在同 Call-ID 下发任意 mid-dialog 请求(INFO PAUSE / TEARDOWN / BYE 等)
 * 破坏正在进行的回放 / 直播会话。
 *
 * 本 verifier 同时校验四元素:
 *  - callId:Call-ID 头匹配
 *  - remoteTag:平台侧 From tag(对设备来说,平台的 tag 走 From,设备走 To)
 *  - remoteSourceIp:envelope.sourceIp 跟建立 dialog 时记录的来源 IP 一致
 *    (P0-1 envelope 让本校验成为可能)
 *  - (本 verifier 不校验 localTag,因为 mid-dialog 请求里平台不一定带 To tag 给我们)
 *
 * 默认给 PlaybackCoordinator 用,后续 InviteCoord(实时 INVITE)接入留下一轮。
 */
internal object DialogIdentityVerifier {

    /**
     * dialog 识别四元组(建立 INVITE 200 时由 Coord 记录,后续 mid-dialog 校验用)。
     *
     * @property callId Call-ID 头(建立 dialog 时记录)
     * @property localTag 本设备给的 To tag(用于自检,本 verifier 不强校验)
     * @property remoteTag 平台侧 From tag
     * @property remoteSourceIp 建立 dialog 时 envelope.sourceIp(P0-1 拿到)。
     *   null = 老数据迁移期间;non-null = 严格 IP 校验。
     */
    data class DialogId(
        val callId: String,
        val localTag: String,
        val remoteTag: String,
        val remoteSourceIp: String?,
    )

    /** 校验结果分类 — 让调用方据此返回合适的 SIP 错误码 / Warning 日志。 */
    enum class VerifyResult {
        /** 通过,继续处理本请求。 */
        Match,
        /** Call-ID 不匹配 → 返回 481 Call/Transaction Does Not Exist(RFC 3261 § 12.2.1.1)。 */
        MismatchCallId,
        /** Call-ID 对但 remote tag 不对 → 返回 481(同上)。 */
        MismatchTag,
        /** Call-ID + tag 都对,sourceIp 不匹配 → 返回 481(也算非法 dialog identity)。 */
        MismatchSource,
    }

    /**
     * 把入栈 envelope 跟已建立的 dialog 比对。
     *
     * @param envelope 当前到达的 mid-dialog 请求(INFO / BYE / CANCEL / ACK)
     * @param dialog 建立时记录的 DialogId
     * @return [VerifyResult]
     */
    fun verify(envelope: SipEnvelope, dialog: DialogId): VerifyResult {
        val req = envelope.message as? SipRequest ?: return VerifyResult.MismatchCallId
        // Call-ID
        val callIdHeader = req.callId() ?: return VerifyResult.MismatchCallId
        if (callIdHeader != dialog.callId) return VerifyResult.MismatchCallId
        // remote tag(平台侧 From tag)
        val fromHeader = req.fromHeader() ?: return VerifyResult.MismatchTag
        val fromTag = SipHeaderHelpers.parseTag(fromHeader)
        if (fromTag.isEmpty() || fromTag != dialog.remoteTag) return VerifyResult.MismatchTag
        // sourceIp(P0-1)
        val expectedIp = dialog.remoteSourceIp
        if (expectedIp != null && envelope.sourceIp != expectedIp) return VerifyResult.MismatchSource
        return VerifyResult.Match
    }
}
