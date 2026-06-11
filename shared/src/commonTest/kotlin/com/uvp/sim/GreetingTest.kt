package com.uvp.sim

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetingIsNonEmpty() {
        val greeting = Greeting().greet()
        assertTrue(greeting.isNotEmpty())
    }
}
