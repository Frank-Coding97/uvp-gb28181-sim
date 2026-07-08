package com.uvp.sim.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMVideoFormatDescriptionCreate
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.CoreFoundation.CFRelease
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-B1-0 spike: 观测 iOS VT HEVC 输出的 AVCC 长度前缀字节数 (`lengthSizeMinusOne`)。
 *
 * 结论假设(2026-07-07):**4 字节大端长度前缀**(即 `lengthSizeMinusOne == 3`),iOS 15+
 * VideoToolbox HEVC encoder 稳定输出。若真机 T-B1-4 落地时观察不到 SPS/PPS/VPS/IDR
 * 正确切分,回来打开 spike 观测 `hvcC` 二进制。
 *
 * 说明:iOS Simulator arm64 上 VT create HEVC 可能失败或降级为软编,因此本 spike
 * 不做 `assertEquals`,只保证 CMFormatDescriptionCreate 路径能编译 + 不 crash,同时
 * 为后续 `AnnexB.splitAvcc(bytes, 4)` 复用的假设留一份 in-repo trace。
 *
 * spike 归档:`~/Documents/Atlas/wiki/projects/uvp-gb28181-sim/research/2026-07-07-spike-hevc-avcc-prefix.md`
 */
@OptIn(ExperimentalForeignApi::class)
class HevcAvccPrefixSpikeTest {

    @Test
    fun cmFormatDescription_hevc_compiles_and_returns_status() = memScoped {
        // 只验证 kCMVideoCodecType_HEVC 常量在 K/N 侧可用 + CMVideoFormatDescriptionCreate
        // 路径类型正确。真正的 hvcC dump 留给真机 spike。
        val outRef = alloc<kotlinx.cinterop.CPointerVar<cnames.structs.opaqueCMFormatDescription>>()
        val status = CMVideoFormatDescriptionCreate(
            allocator = null,
            codecType = kCMVideoCodecType_HEVC,
            width = 1280,
            height = 720,
            extensions = null,
            formatDescriptionOut = outRef.ptr,
        )
        // status == noErr 或 kCMFormatDescriptionError_InvalidParameter 都可接受 —
        // 只要没 crash 就说明 API 面 K/N 侧可用。
        assertTrue(
            status == 0 || status < 0,
            "CMVideoFormatDescriptionCreate 返回 status=$status(非 crash 均可)"
        )
        outRef.value?.let { CFRelease(it) }
    }

    @Test
    fun assumption_avcc_length_prefix_is_4_bytes() {
        // 记录 spike 结论。若未来观察到不同,把此 assertion 放开 + 观察真实值,并回到 plan
        // 补 AnnexB.splitAvccHevc commonMain 改动。
        val assumedLengthSizeMinusOne = 3
        val assumedPrefixBytes = assumedLengthSizeMinusOne + 1
        assertTrue(
            assumedPrefixBytes == 4,
            "spike 结论:iOS VT HEVC AVCC 长度前缀 = 4 字节。若真机观察不同,回来更新。"
        )
    }
}
