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

    private fun grantCoarseLocationOnly() {
        shadowOf(context as Application).grantPermissions(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
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
        // cross-review R1 #2 修复兼容 — 默认取运行时 now,避免硬编码时间戳几年后超过 MAX_FIX_AGE_MS
        // 命中 provider.next() 的年龄校验返回 null 让所有测试挂掉。
        time: Long = System.currentTimeMillis(),
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

    // Codex R1 P1-3 · Android 12+ 用户可以只授 COARSE 不授 FINE,provider 应仍能通过 NETWORK 取位置
    @Test
    fun start_withCoarseOnly_stillAcceptsNetworkFix() {
        grantCoarseLocationOnly()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        // 只有 COARSE 时,GPS_PROVIDER 不注册,但 NETWORK_PROVIDER 应该注册
        simulateFix(
            makeLocation(LocationManager.NETWORK_PROVIDER, 40.0, 116.5, accuracy = 100f)
        )
        val fix = provider.next()
        assertNotNull("COARSE 授权应仍能拿到 NETWORK 位置 fix", fix)
        assertEquals(100f, fix!!.accuracy, 0.0001f)
    }

    // cross-review R2 #1 · GPS/NETWORK 回调乱序到达时,旧 fix 不该覆盖新 fix
    @Test
    fun outOfOrder_callback_rejects_older_fix_even_when_accuracy_ok() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        val baseTime = System.currentTimeMillis()
        // 先注入一个较新的 NETWORK fix (accuracy 20m, t = baseTime)
        simulateFix(
            makeLocation(LocationManager.NETWORK_PROVIDER, 40.0, 116.5, accuracy = 20f, time = baseTime)
        )
        val newer = provider.next()
        assertNotNull(newer)
        assertEquals(40.0, newer!!.point.latitude, 1e-9)
        assertEquals(20f, newer.accuracy, 0.0001f)

        // 再注入一个老 GPS callback(accuracy 15m 满足 tolerance,但 time 早 3s)
        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 15f, time = baseTime - 3_000L)
        )
        val stillNewer = provider.next()
        assertNotNull(stillNewer)
        // 单调守卫必须阻止老 callback 覆盖新 fix,坐标不该回退
        assertEquals("旧 callback 乱序到达,不该覆盖新 fix (R2 #1 fix)", 40.0, stillNewer!!.point.latitude, 1e-9)
        assertEquals(20f, stillNewer.accuracy, 0.0001f)
    }

    // Codex R1 P1-1 · onProviderDisabled 清 latestFix,避免陈旧 GPS 阻挡 NETWORK 新点
    @Test
    fun providerDisabled_clearsLatestFixWhenItsSource() {
        grantFineLocation()
        val provider = AndroidSystemLocationProvider(context)
        provider.start()

        // 先接收高精度 GPS fix
        simulateFix(
            makeLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, accuracy = 8f)
        )
        assertNotNull(provider.next())

        // GPS provider 关闭(用户在系统里禁 GPS,保留 NETWORK)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        pumpMainLooper()

        // latestFix 应该被清 —— 之前 GPS 8m 陈旧值不该继续挡住 NETWORK
        assertNull(
            "陈旧 GPS fix 应该被 onProviderDisabled 清理 (F6 P1-1 fix)",
            provider.next(),
        )

        // NETWORK 新 fix 现在能进来了
        simulateFix(
            makeLocation(LocationManager.NETWORK_PROVIDER, 40.0, 116.5, accuracy = 100f)
        )
        val networkFix = provider.next()
        assertNotNull("GPS 关掉后 NETWORK fix 应能写入", networkFix)
        assertEquals(100f, networkFix!!.accuracy, 0.0001f)
    }
}
