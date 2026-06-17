package com.uvp.sim.network

import com.uvp.sim.config.NetworkPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T2 测试:NetworkState 数据类语义。
 *
 * actual NetworkController 的真实现留 T3(Android,真机 / instrumented)+ T10(真机回归)。
 */
class NetworkStateTest {

    @Test
    fun `Auto is singleton object`() {
        assertEquals(NetworkState.Auto, NetworkState.Auto)
    }

    @Test
    fun `Bound data class equality by content`() {
        val a = NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "192.168.1.10")
        val b = NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "192.168.1.10")
        val c = NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "192.168.1.11")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `Unavailable preserves preference and reason`() {
        val s = NetworkState.Unavailable(NetworkPreference.CELLULAR, "蜂窝不可用(无 SIM 或飞行模式)")
        assertEquals(NetworkPreference.CELLULAR, s.preference)
        assertTrue(s.reason.contains("蜂窝"))
    }

    @Test
    fun `Switching keeps from and to preferences`() {
        val s = NetworkState.Switching(NetworkPreference.WIFI, NetworkPreference.CELLULAR)
        assertEquals(NetworkPreference.WIFI, s.from)
        assertEquals(NetworkPreference.CELLULAR, s.to)
    }

    @Test
    fun `when branches cover all subtypes exhaustively`() {
        // 编译期保证 sealed class 穷尽性,运行期再确认每个分支可命中
        val states = listOf<NetworkState>(
            NetworkState.Auto,
            NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "1.2.3.4"),
            NetworkState.Unavailable(NetworkPreference.WIFI, "off"),
            NetworkState.Switching(NetworkPreference.AUTO, NetworkPreference.WIFI),
        )
        val labels = states.map {
            when (it) {
                NetworkState.Auto -> "auto"
                is NetworkState.Bound -> "bound"
                is NetworkState.Unavailable -> "unavailable"
                is NetworkState.Switching -> "switching"
            }
        }
        assertEquals(listOf("auto", "bound", "unavailable", "switching"), labels)
    }
}
