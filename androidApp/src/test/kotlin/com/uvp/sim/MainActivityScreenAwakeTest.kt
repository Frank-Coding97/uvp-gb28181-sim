package com.uvp.sim

import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityScreenAwakeTest {

    @Test
    fun setsKeepScreenOnWhenWindowIsCreated() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            assertTrue(
                "MainActivity must request screen-on for its visible window",
                controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            )
        } finally {
            controller.destroy()
        }
    }
}
