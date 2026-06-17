package com.uvp.sim.domain

/**
 * 语音广播下行(§9.8)反向 INVITE dialog。设备是 sim 项目里第一次"主动 INVITE 平台"的场景。
 *
 * 单 slot:同时只允许一路 broadcast(spec Q1)。第二路 Broadcast MESSAGE 在持有 dialog 时
 * 直接回 Broadcast Response ERROR/busy,不发 INVITE。
 */
data class BroadcastDialog(
    val callId: String,
    val localTag: String,
    val remoteTag: String?,            // null until 200 OK
    val cseq: Int,                     // INVITE 的 CSeq 序号(ACK 复用,BYE = +1)
    val sourceId: String,              // 平台 ID
    val targetId: String,              // 设备 ID(= config.device.deviceId)
    val sourcePlatformUri: String,     // 'sip:{sourceId}@{domain}'
    val localAudioPort: Int,
    val deviceSsrc: String,
    val remoteAudioHost: String? = null,
    val remoteAudioPort: Int = -1,
    val codec: AudioRxCodec = AudioRxCodec.PCMA,
    val state: BroadcastDialogState = BroadcastDialogState.Inviting,
    val createdAtMs: Long,
    val firstPacketAtMs: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val seqLost: Long = 0L,
    val decodeErrors: Long = 0L
)

enum class BroadcastDialogState {
    BroadcastAcked,    // 收到 MESSAGE Broadcast,已回 200 OK + Broadcast Response
    Inviting,          // 已发 outbound INVITE,等 200 OK
    Talking,           // 收到 200 OK + 已发 ACK,RTP 流转中
    EndingByLocal,     // 用户停止,正在发 BYE
    EndingByRemote,    // 平台先发 BYE,正在 200 OK 回
    Failed             // 中途失败,清状态
}

/** 语音广播 RX 编码(G.711 双形态)。 */
enum class AudioRxCodec(val payloadType: Int, val sampleRateHz: Int) {
    PCMA(8, 8000),
    PCMU(0, 8000);

    companion object {
        fun fromPayloadType(pt: Int): AudioRxCodec? = when (pt) {
            8 -> PCMA
            0 -> PCMU
            else -> null
        }
    }
}
