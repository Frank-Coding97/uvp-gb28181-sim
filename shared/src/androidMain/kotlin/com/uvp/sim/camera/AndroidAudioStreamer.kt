package com.uvp.sim.camera

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.AudioFrame
import com.uvp.sim.media.G711
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Android audio capture + encode pipeline.
 *
 * - **G.711A / G.711U**: AudioRecord 8 kHz mono PCM_16BIT → software A/μ-law encode.
 *   Output frames are 20 ms (160 samples) to match GB28181 / RTP convention.
 * - **AAC**: AudioRecord 16 kHz mono PCM_16BIT → MediaCodec `audio/mp4a-latm`
 *   hardware encoder. Output is raw AAC (no ADTS) at the encoder's natural
 *   frame size (1024 samples ≈ 64 ms).
 */
class AndroidAudioStreamer(
    private val config: AudioCaptureConfig
) {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun stream(): Flow<AudioFrame> = when (config.codec) {
        AudioCodec.G711A, AudioCodec.G711U -> streamG711()
        AudioCodec.AAC -> streamAac()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun streamG711(): Flow<AudioFrame> = callbackFlow {
        val sampleRate = config.sampleRateHz
        val frameSamples = sampleRate / 50  // 20 ms frame: 160 @ 8k, 320 @ 16k
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(frameSamples * 4)
        )
        record = recorder
        recorder.startRecording()

        val pcmBuf = ShortArray(frameSamples)
        val tickStartUs = monotonicMicros()
        var sampleIndex = 0L
        var alive = true
        val readerThread = Thread {
            while (alive && !isClosedForSend) {
                var read = 0
                while (read < frameSamples && alive) {
                    val n = recorder.read(pcmBuf, read, frameSamples - read)
                    if (n <= 0) { alive = false; break }
                    read += n
                }
                if (!alive) break
                val payload = when (config.codec) {
                    AudioCodec.G711A -> G711.encodeAlaw(pcmBuf)
                    AudioCodec.G711U -> G711.encodeUlaw(pcmBuf)
                    else -> error("unreachable")
                }
                val ptsUs = tickStartUs + sampleIndex * 1_000_000 / sampleRate
                sampleIndex += frameSamples
                trySend(AudioFrame(payload, ptsUs, config.codec))
            }
        }.apply {
            name = "uvp-audio-g711"
            isDaemon = true
            start()
        }

        awaitClose {
            alive = false
            runCatching { readerThread.interrupt() }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            record = null
        }
    }.flowOn(Dispatchers.IO)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun streamAac(): Flow<AudioFrame> = callbackFlow {
        val sampleRate = config.sampleRateHz
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, config.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoder = codec
        codec.start()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val frameSamples = 1024  // AAC LC frame size
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 4).coerceAtLeast(frameSamples * 8)
        )
        record = recorder
        recorder.startRecording()

        val tickStartUs = monotonicMicros()
        var sampleIndex = 0L
        var alive = true

        val pcmShorts = ShortArray(frameSamples)
        val pcmBytes = ByteArray(frameSamples * 2)
        val bufInfo = MediaCodec.BufferInfo()

        val readerThread = Thread {
            while (alive && !isClosedForSend) {
                var read = 0
                while (read < frameSamples && alive) {
                    val n = recorder.read(pcmShorts, read, frameSamples - read)
                    if (n <= 0) { alive = false; break }
                    read += n
                }
                if (!alive) break
                // Pack PCM_16BIT as little-endian bytes for MediaCodec.
                for (i in 0 until frameSamples) {
                    val s = pcmShorts[i].toInt()
                    pcmBytes[i * 2] = (s and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                }

                val inputIndex = codec.dequeueInputBuffer(20_000)
                if (inputIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuf.clear()
                    inputBuf.put(pcmBytes)
                    val ptsUs = tickStartUs + sampleIndex * 1_000_000 / sampleRate
                    codec.queueInputBuffer(inputIndex, 0, pcmBytes.size, ptsUs, 0)
                    sampleIndex += frameSamples
                }

                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(bufInfo, 0)
                    if (outIdx < 0) break
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        continue
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val raw = ByteArray(bufInfo.size)
                        outBuf.position(bufInfo.offset)
                        outBuf.get(raw, 0, bufInfo.size)
                        // Wrap with ADTS so downstream players can demux directly.
                        val adts = withAdts(raw, sampleRate, config.channels)
                        trySend(AudioFrame(adts, bufInfo.presentationTimeUs, AudioCodec.AAC))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        }.apply {
            name = "uvp-audio-aac"
            isDaemon = true
            start()
        }

        awaitClose {
            alive = false
            runCatching { readerThread.interrupt() }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            record = null
            encoder = null
        }
    }.flowOn(Dispatchers.IO)

    suspend fun stop() {
        runCatching { record?.stop(); record?.release() }
        runCatching { encoder?.stop(); encoder?.release() }
        record = null
        encoder = null
    }

    private fun monotonicMicros(): Long = System.nanoTime() / 1_000

    private fun withAdts(aac: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        // MPEG-4 AAC LC ADTS header (7 bytes, no CRC).
        val sampleRateIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 11
        }
        val frameLen = aac.size + 7
        val out = ByteArray(frameLen)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()  // MPEG-4, no CRC
        out[2] = (((2 - 1) shl 6) or (sampleRateIdx shl 2) or ((channels shr 2) and 0x01)).toByte()
        out[3] = (((channels and 0x03) shl 6) or ((frameLen ushr 11) and 0x03)).toByte()
        out[4] = ((frameLen ushr 3) and 0xFF).toByte()
        out[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        aac.copyInto(out, 7)
        return out
    }
}
