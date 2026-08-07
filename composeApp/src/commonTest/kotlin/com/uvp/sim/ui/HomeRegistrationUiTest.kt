package com.uvp.sim.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRegistrationUiTest {
    @Test
    fun ready_configuration_allows_registration_when_not_editing() {
        assertTrue(registrationButtonEnabled(configReady = true, sipConfigEditing = false))
    }

    @Test
    fun editing_configuration_disables_registration_even_when_ready() {
        assertFalse(registrationButtonEnabled(configReady = true, sipConfigEditing = true))
    }

    @Test
    fun incomplete_configuration_remains_disabled_after_editing_ends() {
        assertFalse(registrationButtonEnabled(configReady = false, sipConfigEditing = false))
    }
}
