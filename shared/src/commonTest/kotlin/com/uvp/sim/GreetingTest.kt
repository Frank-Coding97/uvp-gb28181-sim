package com.uvp.sim

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetingIsNonEmpty() {
        val greeting = Greeting().greet()
        assertTrue(greeting.isEmpty())  // T5 故意失败演练,下个 commit revert
    }
}
