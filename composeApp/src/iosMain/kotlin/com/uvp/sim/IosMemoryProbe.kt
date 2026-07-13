package com.uvp.sim

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.TASK_VM_INFO
import platform.darwin.TASK_VM_INFO_COUNT
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

/**
 * 进程内存探针 —— 读 mach `task_info(TASK_VM_INFO)` 的 `phys_footprint`。
 *
 * `phys_footprint` 就是 Xcode Debug Gauge / Instruments 里显示的那条内存曲线,
 * 比 `resident_size` 更贴近系统 jetsam 判定用的口径。
 *
 * 用途:接到 [IosAppHost] 诊断心跳,验证"推流几分钟后卡死"是否伴随内存持续爬升
 * (若是,基本锁定 K/N GC / 分配风暴,而非死锁)。全程 runCatching 兜底,读失败返回 -1。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosMemoryProbe {

    /** @return phys_footprint 字节数;读取失败返回 -1。 */
    fun physFootprintBytes(): Long = runCatching {
        memScoped {
            val info = alloc<task_vm_info_data_t>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = TASK_VM_INFO_COUNT
            val kr = task_info(
                target_task = mach_task_self_,
                flavor = TASK_VM_INFO.toUInt(),
                task_info_out = info.ptr.reinterpret(),
                task_info_outCnt = count.ptr,
            )
            if (kr != 0) -1L else info.phys_footprint.toLong()
        }
    }.getOrDefault(-1L)

    /** 便于日志读:字节 → MB(一位小数),-1 原样透传。 */
    fun physFootprintMb(): String {
        val b = physFootprintBytes()
        if (b < 0) return "-1"
        val mb = b.toDouble() / (1024.0 * 1024.0)
        val whole = mb.toLong()
        val frac = ((mb - whole) * 10).toLong()
        return "$whole.$frac"
    }
}
