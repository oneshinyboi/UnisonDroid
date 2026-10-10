package io.unisondroid.app.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionsTest {

    @Test
    fun `battery exemption intent is offered when not exempt`() {
        val context = RuntimeEnvironment.getApplication() as Context
        shadowOf(powerManager(context)).setIgnoringBatteryOptimizations(context.packageName, false)

        val intent = batteryExemptionIntent(context)

        assertNotNull("a non-exempt app must be offered the exemption prompt", intent)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent!!.action)
    }

    @Test
    fun `battery exemption intent is null when already exempt`() {
        val context = RuntimeEnvironment.getApplication() as Context
        shadowOf(powerManager(context)).setIgnoringBatteryOptimizations(context.packageName, true)

        assertNull("an exempt app must not be prompted again", batteryExemptionIntent(context))
    }

    private fun powerManager(context: Context): PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
}
