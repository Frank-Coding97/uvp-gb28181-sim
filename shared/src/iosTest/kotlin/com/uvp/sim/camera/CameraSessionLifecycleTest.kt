package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSessionLifecycleTest {

    @Test
    fun successful_configuration_commits_before_starting() {
        val calls = mutableListOf<String>()

        val started = configureThenStartSession(
            beginConfiguration = { calls += "begin" },
            configure = {
                calls += "configure"
                true
            },
            commitConfiguration = { calls += "commit" },
            startRunning = { calls += "start" },
        )

        assertTrue(started)
        assertEquals(listOf("begin", "configure", "commit", "start"), calls)
    }

    @Test
    fun rejected_configuration_commits_without_starting() {
        val calls = mutableListOf<String>()

        val started = configureThenStartSession(
            beginConfiguration = { calls += "begin" },
            configure = {
                calls += "configure"
                false
            },
            commitConfiguration = { calls += "commit" },
            startRunning = { calls += "start" },
        )

        assertFalse(started)
        assertEquals(listOf("begin", "configure", "commit"), calls)
    }

    @Test
    fun configuration_exception_still_commits_without_starting() {
        val calls = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            configureThenStartSession(
                beginConfiguration = { calls += "begin" },
                configure = {
                    calls += "configure"
                    error("configuration failed")
                },
                commitConfiguration = { calls += "commit" },
                startRunning = { calls += "start" },
            )
        }

        assertEquals(listOf("begin", "configure", "commit"), calls)
    }
}
