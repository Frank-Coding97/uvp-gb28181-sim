package com.uvp.sim.domain.coord

import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.transportErrorOf
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.observability.LogTag
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * cross-review R3 拆分 — 直播 INVITE 媒体管线:PsMuxer + RtpPacker + Video/Audio/RTCP/Stats loop。
 *
 * 设计原则(spec §4 / plan §2.2):
 * - 全部 job **LAZY 启动**(R3 #2 race 治本):主类**赋 activeStream 之后**调 [MediaJobs.startAll]
 *   再激活,确保任一 loop 立即失败时 `onMediaFailure` 回调能看到已发布的 activeStream
 * - **不**直接读写 activeStream / SipState,失败通过 [onMediaFailure] 回调上抛
 * - **不**直接调 stopActiveStream / cleanupActiveStream(主类的 cleanup 入口)
 * - rtpMutex 串行 pack+send,保证 RFC 3550 序列号单调递增(R2 #2 verify-followup 治本)
 *
 * 用法(主类视角):
 * ```
 * val media = mediaPipeline.build(cid, rtp, offer, ssrc, cam) { cid, reason ->
 *     cleanupActiveStream(reason, ...)
 * }
 * activeStream = ActiveStream(..., streamJob=media.streamJob, audioJob=media.audioJob, ...)
 * _activeStreamSnapshot.value = snapshot
 * media.startAll()  // R3 #2:发布后才 start
 * ```
 */
internal class InviteMediaPipeline(
    private val shared: InviteSharedState,
    private val rtpSenderFactory: (host: String, port: Int, mode: RtpMode, expectedClientHost: String?) -> RtpSender,
    private val audioCapture: com.uvp.sim.camera.AudioCapture?,
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val rtcpSrIntervalMs: Long = RTCP_SR_INTERVAL_MS,
    private val mediaStatsIntervalMs: Long = MEDIA_STATS_INTERVAL_MS,
) {

    /**
     * 媒体推流 job 集合:video / audio / RTCP / stats 全 LAZY,需 [startAll] 显式激活。
     */
    internal data class MediaJobs(
        val streamJob: Job,
        val audioJob: Job?,
        val rtcpSender: RtpSender,
        val rtcpJob: Job,
        val statsJob: Job,
    ) {
        /** 主类赋 activeStream 后调,激活所有 LAZY job(idempotent)。 */
        fun startAll() {
            streamJob.start()
            audioJob?.start()
            rtcpJob.start()
            statsJob.start()
        }

        /** 销毁:cancel 全部 job + 关 RTCP sender。idempotent。 */
        suspend fun stop() {
            streamJob.cancel()
            audioJob?.cancel()
            rtcpJob.cancel()
            statsJob.cancel()
            try { rtcpSender.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 装配媒体管线(LAZY)。返回的 [MediaJobs] 内 job 都未启动,主类发布 activeStream 后调
     * [MediaJobs.startAll]。
     *
     * @param onMediaFailure video/audio loop 失败回调,主类典型实现:
     *                       `cleanupActiveStream(reason, SipEvent.CallEnded, callId=cid)`
     */
    suspend fun build(
        cid: String,
        rtp: RtpSender,
        offer: com.uvp.sim.sip.SdpOffer,
        ssrc: String,
        cam: com.uvp.sim.camera.CameraCapture,
        onMediaFailure: suspend (cid: String, reason: String) -> Unit,
    ): MediaJobs {
        val packer = com.uvp.sim.media.RtpPacker(
            payloadType = 96,
            ssrc = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc),
        )
        val muxer = com.uvp.sim.media.PsMuxer().apply {
            audioCodec = if (audioCapture != null) shared.config.video.audioCodec else null
        }
        val rtpMutex = Mutex()

        val streamJob = launchVideoSendLoop(cid, cam, muxer, packer, rtp, rtpMutex, onMediaFailure)
        val audioJob = audioCapture?.let { audio ->
            launchAudioSendLoop(cid, audio, muxer, packer, rtp, rtpMutex, onMediaFailure)
        }

        // RTCP SR sender 在此创建,失败 emit 事件不阻断主流程(跟历史一致)
        val rtcp = rtpSenderFactory(offer.remoteIp, offer.remotePort + 1, RtpMode.UDP, null)
        try { rtcp.bindLocalPort() } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("RTCP bind", e))
        }
        val rtcpJob = launchRtcpSrLoop(ssrc, rtcp, rtpMutex)
        val statsJob = launchStatsLoop(cid, rtpMutex)

        return MediaJobs(streamJob, audioJob, rtcp, rtcpJob, statsJob)
    }

    private fun launchVideoSendLoop(
        cid: String,
        cam: com.uvp.sim.camera.CameraCapture,
        muxer: com.uvp.sim.media.PsMuxer,
        packer: com.uvp.sim.media.RtpPacker,
        rtp: RtpSender,
        rtpMutex: Mutex,
        onMediaFailure: suspend (cid: String, reason: String) -> Unit,
    ): Job = shared.scope.launch(start = CoroutineStart.LAZY) {
        try {
            cam.start().collect { frame ->
                val ps = muxer.muxFrame(frame)
                val timestamp90k = frame.timestampUs * 9 / 100
                // R2 #2 verify-followup: pack 跟 send 在同一把 mutex,RFC 3550 序列号单调递增
                // R4 #2:frameCount 一同收进 rtpMutex 保护范围,避免 stats/RTCP loop torn read
                rtpMutex.withLock {
                    val packets = packer.packFrame(ps, timestamp90k)
                    for (p in packets) {
                        rtp.send(p)
                        shared.currentActiveStream()?.let {
                            it.packetCount += 1
                            it.octetCount += (p.size - 12).coerceAtLeast(0).toLong()
                            it.lastRtpTimestamp = timestamp90k
                        }
                    }
                    shared.currentActiveStream()?.let { it.frameCount += 1 }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("RTP video send", e))
            shared.scope.launch { onMediaFailure(cid, "video send failed: ${e::class.simpleName}") }
        }
    }

    private fun launchAudioSendLoop(
        cid: String,
        audio: com.uvp.sim.camera.AudioCapture,
        muxer: com.uvp.sim.media.PsMuxer,
        packer: com.uvp.sim.media.RtpPacker,
        rtp: RtpSender,
        rtpMutex: Mutex,
        onMediaFailure: suspend (cid: String, reason: String) -> Unit,
    ): Job = shared.scope.launch(start = CoroutineStart.LAZY) {
        try {
            var audioFrameCount = 0L
            audio.start().collect { aFrame ->
                audioFrameCount += 1
                if (audioFrameCount == 1L) {
                    com.uvp.sim.observability.SystemLogger.emit(
                        com.uvp.sim.observability.LogLevel.Info,
                        LogTag.Media,
                        "RTP_AUDIO_FIRST_FRAME codec=${aFrame.codec} payload=${aFrame.payload.size}",
                    )
                }
                val ps = muxer.muxAudio(aFrame)
                val timestamp90k = aFrame.timestampUs * 9 / 100
                rtpMutex.withLock {
                    val packets = packer.packFrame(ps, timestamp90k)
                    for (p in packets) {
                        rtp.send(p)
                        shared.currentActiveStream()?.let {
                            it.packetCount += 1
                            it.octetCount += (p.size - 12).coerceAtLeast(0).toLong()
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("RTP audio send", e))
            shared.scope.launch { onMediaFailure(cid, "audio send failed: ${e::class.simpleName}") }
        }
    }

    private fun launchRtcpSrLoop(ssrc: String, rtcp: RtpSender, rtpMutex: Mutex): Job {
        val ssrcInt = com.uvp.sim.sip.SsrcUtils.toRtpInt(ssrc)
        return shared.scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                delay(rtcpSrIntervalMs)
                val a = shared.currentActiveStream() ?: break
                // R4 #2:atomic snapshot 4 个 counter,避免 torn read
                val snapshot = rtpMutex.withLock {
                    Quad(a.lastRtpTimestamp, a.packetCount.toLong(), a.octetCount, 0L)
                }
                runCatching {
                    val sr = com.uvp.sim.rtp.RtcpSender.buildSR(
                        ssrc = ssrcInt,
                        ntpEpochMs = clockOffsetProvider().adjustedNowMs(),
                        rtpTimestamp = snapshot.a,
                        senderPacketCount = snapshot.b,
                        senderOctetCount = snapshot.c,
                    )
                    rtcp.send(sr)
                }
            }
        }
    }

    private data class Quad(val a: Long, val b: Long, val c: Long, val d: Long)

    private fun launchStatsLoop(cid: String, rtpMutex: Mutex): Job =
        shared.scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                delay(mediaStatsIntervalMs)
                val a = shared.currentActiveStream() ?: break
                val (frameCount, packetCount) = rtpMutex.withLock { a.frameCount to a.packetCount }
                com.uvp.sim.observability.SystemLogger.emit(
                    com.uvp.sim.observability.LogLevel.Info, LogTag.Media,
                    "RTP 推送中: $frameCount 帧 / $packetCount 包"
                )
                shared.simEventEmit(
                    com.uvp.sim.domain.SimEvent.StreamStats(
                        callId = a.callId,
                        frameCount = frameCount,
                        packetCount = packetCount,
                    )
                )
            }
        }

    companion object {
        const val RTCP_SR_INTERVAL_MS: Long = 5_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
    }
}
