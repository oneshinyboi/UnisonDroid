package io.unisondroid.app.service

import android.content.Context
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceLocatorTest {

    @After
    fun tearDown() {
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `engine is cached per provider`() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()

        val first = ServiceLocator.engine(context)
        val second = ServiceLocator.engine(context)

        assertSame("engine must be a per-process singleton for a given provider", first, second)
    }
}
