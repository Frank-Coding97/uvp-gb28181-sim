package com.uvp.sim.camera

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * T-P2-1: [IosCameraController.requestEncoding] refCount + generation counter 语义。
 *
 * Fake encoding 阶段 —— VT session 未真启,只测 [IosCameraController.encodingActive]
 * StateFlow 语义 + handle stale/idempotent 边界。真 VT lifecycle 由 T-P2-2 验。
 *
 * 每 test 后强制 stopPreview 归零,避免跨 test state 污染(IosCameraController 是全局单例)。
 */
class EncodingHandleRefCountTest {

    @AfterTest
    fun cleanup() = runTest {
        // 强制 controller 归零,让下一个 test 从干净 state 起手
        IosCameraController.stopPreview()
    }

    @Test
    fun first_request_starts_encoding_active_true() {
        assertFalse(IosCameraController.encodingActive.value, "precondition: encoding must be inactive")
        val h = IosCameraController.requestEncoding()
        assertTrue(IosCameraController.encodingActive.value, "first request should activate encoding")
        h.close()
    }

    @Test
    fun second_request_reuses_encoding_refCount_2() {
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        assertTrue(IosCameraController.encodingActive.value)
        assertNotSame(a, b, "each request returns a distinct handle")
        a.close()
        b.close()
    }

    @Test
    fun close_one_of_two_keeps_encoding_active() {
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        a.close()
        assertTrue(IosCameraController.encodingActive.value, "encoding still active while b holds a ref")
        b.close()
    }

    @Test
    fun close_both_stops_encoding_active_false() {
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        a.close()
        b.close()
        assertFalse(IosCameraController.encodingActive.value, "encoding stops after last handle close")
    }

    @Test
    fun close_is_idempotent_second_call_no_op() {
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        a.close()
        a.close()  // idempotent — must NOT decrement refCount again
        assertTrue(IosCameraController.encodingActive.value, "double-close of a must not stop encoding while b holds a ref")
        b.close()
    }

    @Test
    fun stopPreview_forces_refCount_zero_and_stales_all_handles() = runTest {
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        assertTrue(IosCameraController.encodingActive.value)

        IosCameraController.stopPreview()  // generation++, refCount=0, encodingActive=false
        assertFalse(IosCameraController.encodingActive.value, "stopPreview must force encoding to inactive")

        // 后续 close 因 generation mismatch 变 no-op — 不 crash 且不影响后续新一代
        a.close()
        b.close()
        assertFalse(IosCameraController.encodingActive.value, "stale handle close must remain no-op")
    }

    @Test
    fun request_after_stopPreview_starts_new_generation() = runTest {
        val old = IosCameraController.requestEncoding()
        IosCameraController.stopPreview()

        val fresh = IosCameraController.requestEncoding()
        assertTrue(IosCameraController.encodingActive.value, "request in a new generation must start encoding again")

        // 旧 handle close 走 stale 路径,不影响 fresh
        old.close()
        assertTrue(IosCameraController.encodingActive.value, "stale close of old handle must not stop fresh encoding")

        fresh.close()
        assertFalse(IosCameraController.encodingActive.value)
    }

    @Test
    fun handle_frames_flow_is_shared_hot_flow_after_p2_2() {
        // T-P2-2:frames 换成真 SharedFlow(所有 handle 共享同一份广播源)。
        // 断言:任何一个 handle 的 frames 应该是 SharedFlow 类型(hot flow,不 auto-complete)。
        // Simulator 无 camera 帧不会真 emit,但类型断言不需要 collect,可跑通。
        val h = IosCameraController.requestEncoding()
        try {
            val f = h.frames
            assertTrue(
                f is kotlinx.coroutines.flow.SharedFlow<*>,
                "T-P2-2 后 handle.frames 应为 SharedFlow,当前是 ${f::class.simpleName}"
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun two_handles_share_the_same_frames_flow_instance() {
        // T-P2-2:两个 handle 拿到的 frames 应该指向同一份 SharedFlow(广播源共享)。
        // 通过 identity(===)确认引用相同,而不是内容比较。
        val a = IosCameraController.requestEncoding()
        val b = IosCameraController.requestEncoding()
        try {
            assertTrue(
                a.frames === b.frames,
                "两个 handle.frames 应引用同一份 SharedFlow instance(广播源共享)"
            )
        } finally {
            a.close()
            b.close()
        }
    }
}
