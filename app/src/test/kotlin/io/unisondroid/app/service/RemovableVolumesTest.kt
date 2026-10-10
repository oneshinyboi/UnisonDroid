package io.unisondroid.app.service

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemovableVolumesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `app-private paths are not on a removable volume`() {
        assertFalse(RemovableVolumes.isRemovable(context, context.filesDir.absolutePath))
    }

    @Test
    fun `unknown paths fall back to case sensitive`() {
        // The contract is a safe default: if no storage volume matches, the path
        // is not removable, so the generated profile stays case-sensitive.
        assertFalse(RemovableVolumes.isRemovable(context, "/definitely/not/a/volume"))
    }
}
