package com.uvp.sim.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.osd.OsdRenderer
import com.uvp.sim.osd.OsdRendererHolder
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单段录像 pipeline — 跟工业 IPC encoder 路径同构。
 *
 * ```
 * OsdRendererHolder(单一画面源,直播+录像共享)
 *      ↓ blit(单帧 fbo 已烧 OSD)
 * MediaCodec encoder.inputSurface
 *      ↓ H.264/H.265 编码
 * MediaMuxer.writeSampleData → mp4 文件
 * ```
 *
 * 跟原 VideoCapture<Recorder> 路径的区别:
 * - 帧源:OsdRenderer 输出(已烧 OSD),不是摄像头直连
 * - 编码:手写 MediaCodec,跟直播 encoder 平行注册
 * - mp4 写入:手写 MediaMuxer,而非 Recorder 黑盒
 *
 * 单段生命周期:
 * - [start] 创建 encoder + muxer,注册到 OsdRendererHolder
 * - 编码异步进行(MediaCodec callback)
 * - [stop] signal EOS + 等待 finalize → 回调 [onFinalize](RecordingFile?, error)
 *
 * 切片接力由调用方(AndroidRecordingService)管理,本类只管单段。
 */
internal class OsdRecordingPipeline(
    private val context: Context,
    private val osdConfigFlow: StateFlow<OsdConfig>,
    private val widthPx: Int,
    private val heightPx: Int,
    private val frameRate: Int,
    private val bitrateBps: Int,
    private val keyframeIntervalSeconds: Int,
    private val outputFile: File,
    private val tag: String = TAG_DEFAULT
) {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var encoderInputSurface: Surface? = null
    private var osdRenderer: OsdRenderer? = null

    private var muxerVideoTrack: Int = -1
    private var muxerStarted = false

    private val running = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)

    /** finalize 回调,start 时注入。参数:(成功的输出文件 or null, 异常 or null)。 */
    private var onFinalize: ((File?, Throwable?) -> Unit)? = null

    /**
     * 启动录像 pipeline。失败抛异常(调用方 catch 后走错误路径)。
     *
     * @param onFinalize 录像完成回调,在 stop() 触发的 finalize 后异步执行
     */
    fun start(onFinalize: (File?, Throwable?) -> Unit) {
        if (running.getAndSet(true)) error("OsdRecordingPipeline already running")
        this.onFinalize = onFinalize

        outputFile.parentFile?.mkdirs()

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, widthPx, heightPx
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            encoder = codec
            encoderInputSurface = surface

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) { /* surface input */ }

                override fun onOutputBufferAvailable(
                    c: MediaCodec, index: Int, info: MediaCodec.BufferInfo
                ) {
                    handleOutput(c, index, info)
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                    SystemLogger.emit(LogLevel.Error, LogTag.Media, "录像 encoder 异常: ${e.message}")
                    finalize(error = e)
                }

                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                    val m = muxer ?: return
                    if (muxerVideoTrack < 0) {
                        muxerVideoTrack = m.addTrack(format)
                        m.start()
                        muxerStarted = true
                    }
                }
            })
            codec.start()

            // 注册到 OsdRendererHolder(直播也是这个 holder,共享单一画面源)
            val renderer = OsdRendererHolder.acquire(
                context = context,
                configFlow = osdConfigFlow,
                targetWidth = widthPx,
                targetHeight = heightPx
            ) ?: throw RuntimeException("OsdRenderer 启动失败,无法录像")
            renderer.addEncoderSurface(tag, surface, widthPx, heightPx)
            osdRenderer = renderer

            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "录像 pipeline 启动 → ${widthPx}x${heightPx}@${frameRate}fps,${bitrateBps / 1000}kbps,${outputFile.name}"
            )
        } catch (t: Throwable) {
            running.set(false)
            cleanup()
            throw t
        }
    }

    /**
     * 停止录像 — signal EOS,等编码 finalize 后通过回调返回结果。
     *
     * **不阻塞调用线程**,finalize 通过 [onFinalize] 异步通知。
     */
    fun stop() {
        if (!running.get()) return
        runCatching {
            encoder?.signalEndOfInputStream()
        }.onFailure {
            // 即便 signal 失败也要走 finalize 路径
            finalize(error = it)
        }
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return runCatching { codec.releaseOutputBuffer(index, false) }.let {}

        try {
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            // CODEC_CONFIG buffer 不写 muxer(MediaMuxer 自动从 format 拿 csd-0/csd-1)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            if (info.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                m.writeSampleData(muxerVideoTrack, buffer, info)
            }
            codec.releaseOutputBuffer(index, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                finalize(error = null)
            }
        } catch (t: Throwable) {
            runCatching { codec.releaseOutputBuffer(index, false) }
            finalize(error = t)
        }
    }

    private fun finalize(error: Throwable?) {
        if (finalized.getAndSet(true)) return
        running.set(false)
        cleanup()
        val cb = onFinalize
        onFinalize = null
        cb?.invoke(if (error == null) outputFile else null, error)
    }

    private fun cleanup() {
        runCatching { osdRenderer?.removeEncoderSurface(tag) }
        if (osdRenderer != null) {
            runCatching { OsdRendererHolder.release() }
            osdRenderer = null
        }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderInputSurface?.release() }
        encoder = null
        encoderInputSurface = null
        if (muxerStarted) {
            runCatching { muxer?.stop() }
            muxerStarted = false
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerVideoTrack = -1
    }

    companion object {
        const val TAG_DEFAULT = "record"
    }
}
