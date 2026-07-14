package com.uvp.sim.location

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.uvp.sim.domain.location.AndroidSystemLocationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * plan §5.1 T5 Robolectric 契约测试 —
 * 验证 [AndroidSystemLocationProvider] 权限守卫 / 精度择优 / 幂等 start/stop。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidSystemLocationProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val lm: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun grantFineLocation() {
        shadowOf(context as Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    /** Robolectric 下 LocationListener 是通过 main looper 派发的,注入 fix 后要 idle 才生效。 */
    private fun pumpMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun simulateFix(loc: Location) {
        shadowOf(lm).simulateLocation(loc)
        pumpMainLooper()
    }

    private fun makeLocation(
        provider: String,
        lat: Double,
        lng: Double,
        accuracy: Float,
        speedMps: Float = 0f,
        time: Long = 1_700_000_000_000L,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lng
        this.accuracy = accuracy
        speed = speedMps
        this.time = time
    }

    @Test
    fun start_withoutPermission_isNoOp() {
        val provider = AndroidSystemLocationProvider(context)
        // no permission granted intentionally
        provider.start()
        assertNull("next() 权限缺失时必须为 null", provider.next())
    }

    @Test
    fun accuracy_favoursHigherPrecisionFix() {
        // codex R1 P1 核心回归 —— 低精度不能覆写高精度
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        // 先注入高精度 GPS fix
        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 8f)
        )
        val afterGps = provider.next()
        assertNotNull(afterGps)
        assertEquals(8f, afterGps!!.accuracy, 0.0001f)

        // 再注入低精度 NETWORK fix — 不应覆写
        simulateFix(
            makeLocation(LocationManager.NETWORK_PROVIDER, 40.0, 116.5, accuracy = 200f)
        )
        val afterNetwork = provider.next()
        assertNotNull(afterNetwork)
        assertEquals("高精度 fix 不能被低精度覆写", 8f, afterNetwork!!.accuracy, 0.0001f)
        assertEquals("高精度坐标必须保留", 39.9, afterNetwork.point.latitude, 1e-9)
    }

    @Test
    fun accuracy_lowerPrecisionAcceptedWhenNoPriorFix() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        // 空 latestFix,即使是低精度也接受(避免完全无 fix)
        simulateFix(
            makeLocation(LocationManager.NETWORK_PROVIDER, 40.0, 116.5, accuracy = 200f)
        )
        val fix = provider.next()
        assertNotNull(fix)
        assertEquals(200f, fix!!.accuracy, 0.0001f)
    }

    @Test
    fun stop_clearsLatestFix() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 8f)
        )
        assertNotNull(provider.next())

        provider.stop()
        assertNull("stop() 后 latestFix 必须清 null", provider.next())
    }

    @Test
    fun start_isIdempotent() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()
        provider.start() // 二次调不应抛
        // 注入 fix 仍能被接收
        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 5f)
        )
        assertNotNull(provider.next())
    }

    @Test
    fun speed_isPassedThroughInMetersPerSecond() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()
        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 5f, speedMps = 10f)
        )
        val fix = provider.next()
        assertNotNull(fix)
        // speed 应为 10.0 m/s(km/h 换算在 builder 层做,不在 provider 里)
        assertEquals(10.0, fix!!.speed, 1e-9)
    }

    @Test
    fun stop_isIdempotentWhenNeverStarted() {
        val provider = AndroidSystemLocationProvider(context)
        provider.stop() // 不应抛
        assertTrue(true)
    }
}
