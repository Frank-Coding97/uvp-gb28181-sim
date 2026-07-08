package com.uvp.sim.camera

import com.uvp.sim.media.VideoCodec

/**
 * iOS 侧 actual — 读 [IosCameraController.hevcHwEncodeSupported] 判是否 offer H.265。
 *
 * hevcHwEncodeSupported 尚未探测(App 冷启后首次调用)时,值为 null,保守回答"只 H.264"
 * 避免误 offer 到老机型不支持的 codec。真机路径 `applyCaptureConfig` / `wireCaptureSession`
 * 前会触发 [HevcHwProbe.probe] 填缓存。
 */
actual object PlatformVideoCapabilities {
    actual fun supportedVideoCodecs(): List<VideoCodec> {
        val hevcOk = IosCameraController.hevcHwEncodeSupported
        return if (hevcOk == true) {
            listOf(VideoCodec.H264, VideoCodec.H265)
        } else {
            listOf(VideoCodec.H264)
        }
    }
}
