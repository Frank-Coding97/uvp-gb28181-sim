package com.uvp.sim.recording

import com.uvp.sim.domain.PlaybackBuilder
import com.uvp.sim.domain.PlaybackSession
import com.uvp.sim.network.RtpSender
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.PlaybackOffer
import kotlinx.coroutines.CoroutineScope

/**
 * androidMain 实现 — 把 [AndroidMp4DemuxSource] / [RtpSender] / [PlaybackEngine]
 * 串成 SimulatorEngine 可调度的 PlaybackSession。
 *
 * SimulatorEngine 收到 PLAYBACK INVITE 时调 [build] 拿 session,sendBye 时调 cancel。
 * 不在这里负责 SDP 协商或互斥保护 — 那是 SimulatorEngine 的事。
 */
class AndroidPlaybackBuilder(
    private val scope: CoroutineScope,
    private val rtpSenderFactory: (host: String, port: Int, mode: com.uvp.sim.network.RtpMode) -> RtpSender,
    private val audioCodec: com.uvp.sim.media.AudioCodec = com.uvp.sim.media.AudioCodec.AAC,
) : PlaybackBuilder {

    override suspend fun build(
        offer: PlaybackOffer,
        segments: List<RecordingFile>,
        ssrc: String
    ): PlaybackSession? {
        if (segments.isEmpty()) return null

        // PLAYBACK 默认走 UDP — 平台请求里 m=video 行带 RTP/AVP-TCP 时再扩展
        val sender = rtpSenderFactory(offer.remoteIp, offer.remotePort, com.uvp.sim.network.RtpMode.UDP)
        val localPort = runCatching { sender.bindLocalPort() }
            .onFailure {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Media,
                    "PLAYBACK RTP bind 失败: ${it.message}"
                )
            }
            .getOrElse {
                runCatching { sender.close() }
                return null
            }

        val sink = object : RtpSink {
            private var sent = 0L
            private var firstLogged = false

            override suspend fun send(packet: ByteArray) {
                try {
                    sender.send(packet)
                } catch (e: Throwable) {
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Media,
                        "PLAYBACK RTP send 失败 第${sent + 1}包: ${e.message}"
                    )
                    throw e
                }
                sent += 1
                if (!firstLogged) {
                    firstLogged = true
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "PLAYBACK 首包 → ${packet.size}B → ${offer.remoteIp}:${offer.remotePort}"
                    )
                }
                if (sent % 200 == 0L) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "PLAYBACK 已发 ${sent} 包"
                    )
                }
            }

            override suspend fun close() = sender.close()
        }

        val engine = PlaybackEngine(
            segments = segments,
            demuxFactory = { path -> AndroidMp4DemuxSource(path) },
            framePacker = DefaultFramePacker(
                packer = com.uvp.sim.media.RtpPacker(
                    payloadType = 96,
                    ssrc = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc)
                )
            ),
            rtp = sink,
            clock = WallClock,
            audioCodec = audioCodec
        )

        return AndroidPlaybackSession(
            engine = engine,
            sink = sink,
            localRtpPort = localPort
        )
    }

    private class AndroidPlaybackSession(
        private val engine: PlaybackEngine,
        private val sink: RtpSink,
        override val localRtpPort: Int
    ) : PlaybackSession {

        override suspend fun run() {
            // sink 不在这里 close — SimulatorEngine 在 sendBye 完成后才 cancel,
            // 早关导致 RTP 流静默→BYE 之间有空窗,WVP 端日志难看。
            engine.run()
        }

        override suspend fun cancel() {
            runCatching { sink.close() }
        }
    }

    private object WallClock : PlaybackClock {
        override fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}
