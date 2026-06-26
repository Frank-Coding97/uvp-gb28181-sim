package com.uvp.sim.domain.coord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 父接口 [Coordinator] + [RoutingResult] sealed class 的契约测试。
 *
 * 这是 PR1 T1.1 的 RED→GREEN 测试。Coordinator 接口本身无逻辑(全是 abstract),
 * 所以本测试只验证 RoutingResult sealed class 的代数构造正确。
 */
class RoutingResultTest {

    @Test
    fun handled_is_singleton_object() {
        val a: RoutingResult = RoutingResult.Handled
        val b: RoutingResult = RoutingResult.Handled
        assertTrue(a === b, "Handled 应该是 data object 单例")
    }

    @Test
    fun skip_is_singleton_object() {
        val a: RoutingResult = RoutingResult.Skip
        val b: RoutingResult = RoutingResult.Skip
        assertTrue(a === b, "Skip 应该是 data object 单例")
    }

    @Test
    fun error_carries_reason() {
        val e: RoutingResult = RoutingResult.Error("transport closed")
        assertTrue(e is RoutingResult.Error)
        assertEquals("transport closed", e.reason)
    }

    @Test
    fun error_equality_by_reason() {
        val e1 = RoutingResult.Error("io fail")
        val e2 = RoutingResult.Error("io fail")
        val e3 = RoutingResult.Error("other")
        assertEquals(e1, e2, "data class 同 reason 应 equals")
        assertNotEquals(e1, e3, "不同 reason 应 not equals")
    }

    @Test
    fun handled_and_skip_are_distinct() {
        val h: RoutingResult = RoutingResult.Handled
        val s: RoutingResult = RoutingResult.Skip
        assertNotEquals(h, s)
    }

    @Test
    fun exhaustive_when_compiles() {
        // sealed class 应支持 exhaustive when。本测试本质是编译期检查。
        val cases: List<RoutingResult> = listOf(
            RoutingResult.Handled,
            RoutingResult.Skip,
            RoutingResult.Error("x"),
        )
        cases.forEach { r ->
            val label: String = when (r) {
                RoutingResult.Handled -> "h"
                RoutingResult.Skip -> "s"
                is RoutingResult.Error -> "e:${r.reason}"
            }
            assertTrue(label.isNotEmpty())
        }
    }
}
