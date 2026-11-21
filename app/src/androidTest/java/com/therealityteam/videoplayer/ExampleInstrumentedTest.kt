package com.therealityteam.videoplayer

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.assertEquals
import com.therealityteam.videoplayer.platform.LaunchActivity

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
        val launcher = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.component
        assertEquals(appContext.packageName, launcher?.packageName)
        assertEquals(LaunchActivity::class.java.name, launcher?.className)
    }
}
