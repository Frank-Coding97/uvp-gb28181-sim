package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.network.NetworkState
import com.uvp.sim.ui.model.NetworkStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NetworkStateMapperTest {

    @Test
    fun auto_maps_to_dto_auto() {
        assertEquals(NetworkStateDto.Auto, (NetworkState.Auto as NetworkState).toDto())
    }

    @Test
    fun bound_preserves_preference_interface_ip() {
        val dto = NetworkState.Bound(NetworkPreference.WIFI, "wlan0", "192.168.1.1").toDto()
        assertIs<NetworkStateDto.Bound>(dto)
        assertEquals(NetworkPreference.WIFI, dto.preference)
        assertEquals("wlan0", dto.interfaceName)
        assertEquals("192.168.1.1", dto.localIp)
    }

    @Test
    fun unavailable_preserves_preference_reason() {
        val dto = NetworkState.Unavailable(NetworkPreference.CELLULAR, "No SIM").toDto()
        assertIs<NetworkStateDto.Unavailable>(dto)
        assertEquals(NetworkPreference.CELLULAR, dto.preference)
        assertEquals("No SIM", dto.reason)
    }

    @Test
    fun switching_preserves_from_to() {
        val dto = NetworkState.Switching(NetworkPreference.WIFI, NetworkPreference.CELLULAR).toDto()
        assertIs<NetworkStateDto.Switching>(dto)
        assertEquals(NetworkPreference.WIFI, dto.from)
        assertEquals(NetworkPreference.CELLULAR, dto.to)
    }
}
