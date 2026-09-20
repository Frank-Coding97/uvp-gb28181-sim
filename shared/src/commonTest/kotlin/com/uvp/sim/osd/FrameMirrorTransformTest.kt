package com.uvp.sim.osd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FrameMirror`（GB/T 28181-2022 A.2.1.23）码值 → 渲染形态的映射。
 *
 * ⭐ 这个测试是**两个平台共用的那份唯一映射**的防回归锚点：Android GL 与 iOS CoreImage
 * 都只消费 `mirrorX` / `mirrorY`，谁哪天"顺手"在各平台里再写一份 `when`，这里测不到；
 * 但只要有人动 [FrameMirrorTransform.of] 的对应关系，下面几条必然红。
 *
 * ⛔ 值域以**国标原文**为准：`1` 是水平（左右）、`2` 是上下 ——
 * 本仓前端 `MIRROR_OPTIONS` 曾把这两个写反（那是照海康 ISP 口径编的），
 * 所以下面 `value1IsHorizontalOnly` / `value2IsVerticalOnly` 是**故意分开写**的两条。
 */
class FrameMirrorTransformTest {

    @Test
    fun value0IsIdentity() {
        val t = FrameMirrorTransform.of(0)
        assertFalse(t.mirrorX)
        assertFalse(t.mirrorY)
        assertTrue(t.isIdentity)
    }

    @Test
    fun value1IsHorizontalOnly() {
        val t = FrameMirrorTransform.of(1)
        assertTrue(t.mirrorX, "A.2.1.23 的 1 = 水平镜像（左右翻转）")
        assertFalse(t.mirrorY, "1 不该翻上下 —— 翻了就是与 2 写反")
        assertFalse(t.isIdentity)
    }

    @Test
    fun value2IsVerticalOnly() {
        val t = FrameMirrorTransform.of(2)
        assertFalse(t.mirrorX, "2 不该翻左右 —— 翻了就是与 1 写反")
        assertTrue(t.mirrorY, "A.2.1.23 的 2 = 上下镜像（上下翻转）")
        assertFalse(t.isIdentity)
    }

    @Test
    fun value3IsCenter() {
        val t = FrameMirrorTransform.of(3)
        assertTrue(t.mirrorX, "A.2.1.23 的 3 = 中心镜像（上下左右都翻）")
        assertTrue(t.mirrorY)
        assertFalse(t.isIdentity)
    }

    @Test
    fun nullMeansPlatformNeverConfigured() {
        // 平台从没下发过 —— 与"配了 0"在画面上等价，都返回不翻。
        // 「未配置 / 不启用」两态的区分在展示层（读 deviceConfigs.frameMirror == null），
        // 不靠这里丢信息，所以这条断言是刻意的。
        val t = FrameMirrorTransform.of(null)
        assertTrue(t.isIdentity)
        assertEquals(FrameMirrorTransform.NONE, t)
    }

    @Test
    fun outOfRangeValuesFallBackToIdentity() {
        // 越界值 parse 阶段就会拒（落不进库），这里是第二道闸：
        // 真漏进来也只表现为"不翻"，不会翻成不可预期的方向。
        for (v in listOf(-1, 4, 7, 99)) {
            assertTrue(FrameMirrorTransform.of(v).isIdentity, "value=$v 应回退为不翻")
        }
    }

    @Test
    fun identityOnlyWhenBothAxesOff() {
        assertTrue(FrameMirrorTransform().isIdentity)
        assertTrue(FrameMirrorTransform.NONE.isIdentity)
        assertFalse(FrameMirrorTransform(mirrorX = true).isIdentity)
        assertFalse(FrameMirrorTransform(mirrorY = true).isIdentity)
        assertFalse(FrameMirrorTransform(mirrorX = true, mirrorY = true).isIdentity)
    }
}
