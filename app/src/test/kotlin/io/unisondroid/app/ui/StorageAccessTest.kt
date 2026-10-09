package io.unisondroid.app.ui

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageAccessTest {

    @Test
    fun `all files access grant launches the app scoped settings page`() {
        val context = RuntimeEnvironment.getApplication() as Context

        val intent = allFilesAccessIntent(context)

        assertNotNull("API 30+ must offer a settings grant intent", intent)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent!!.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
