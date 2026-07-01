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
 * iOS 实现,镜像 [AndroidPlaybackBuilder]。仅把 demuxFactory 换为 [IosMp4DemuxSource]
 * (AVAssetReader 抽帧),其余复用 commonMain 的 PlaybackEngine / RtpPacker / DefaultFramePacker。
 */
class IosPlaybackBuilder(
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
            demuxFactory = { path -> IosMp4DemuxSource(path) },
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

        return IosPlaybackSession(
            engine = engine,
            sink = sink,
            localRtpPort = localPort
        )
    }

    private class IosPlaybackSession(
        private val engine: PlaybackEngine,
        private val sink: RtpSink,
        override val localRtpPort: Int
    ) : PlaybackSession {

        override suspend fun run() {
            engine.run()
        }

        override suspend fun cancel() {
            runCatching { sink.close() }
        }

        override fun setScale(scale: Double) = engine.setScale(scale)
        override fun pause() = engine.pause()
        override fun resume() = engine.resume()
        override fun seek(targetMs: Long) = engine.seek(targetMs)
        override val progressMs = engine.progressFlow
        override val totalDurationMs: Long get() = engine.totalDurationMs
    }

    private object WallClock : PlaybackClock {
        override fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}
