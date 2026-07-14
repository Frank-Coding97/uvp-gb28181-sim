package com.uvp.sim.domain.location

import com.uvp.sim.config.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * plan §3.1 contract test — 覆盖 [LocationProvider] 接口契约的关键行为。
 *
 * MockGpsSource 是测试用 impl(next() 每次现算,无 start/stop 副作用),
 * 契约测试用它验证接口语义;真实平台 impl(Android/iOS)有自己的平台测试。
 */
class LocationProviderContractTest {

    @Test
    fun nextOrSkipInvokesBlockWhenFixNonNull() {
        val provider: LocationProvider = object : LocationProvider {
            override fun start() = Unit
            override fun stop() = Unit
            override fun next() = PositionFix(
                point = GeoPoint(longitude = 116.4, latitude = 39.9),
                speed = 1.0, direction = 90.0, altitude = 0.0,
                accuracy = 5.0f, fixTimeMs = 1_700_000_000_000L,
            )
        }
        var captured: PositionFix? = null
        provider.nextOrSkip { captured = it }
        assertTrue(captured != null, "next() 非 null 时 block 必须执行")
        assertEquals(116.4, captured!!.point.longitude)
    }

    @Test
    fun nextOrSkipSkipsBlockWhenFixNull() {
        val provider: LocationProvider = object : LocationProvider {
            override fun start() = Unit
            override fun stop() = Unit
            override fun next() = null
        }
        var invoked = false
        provider.nextOrSkip { invoked = true }
        assertFalse(invoked, "next() null 时 block 不该执行")
    }

    @Test
    fun mockGpsSourceImplementsContract() {
        // MockGpsSource 现在实现 LocationProvider 接口
        val provider: LocationProvider = com.uvp.sim.domain.MockGpsSource(
            GeoPoint(longitude = 116.4, latitude = 39.9)
        )
        // start / stop 幂等 no-op
        provider.start()
        provider.start()
        provider.stop()
        // next() 每次都返回非 null(MockGpsSource 是纯计算,不依赖硬件启动)
        val fix = provider.next()
        assertTrue(fix != null)
        assertTrue(fix.accuracy > 0f, "PositionFix.accuracy 必须 > 0 满足契约")
        assertTrue(fix.fixTimeMs > 0L, "PositionFix.fixTimeMs 必须 > 0 满足契约")
    }
}
