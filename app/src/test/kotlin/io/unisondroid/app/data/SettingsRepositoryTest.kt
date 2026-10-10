package io.unisondroid.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {
    @Test fun `defaults to allowing mobile data`(@TempDir dir: File) = runTest {
        assertEquals(AppSettings(syncOnMobileData = true), SettingsRepository(JsonStore(dir)).get())
    }
    @Test fun `set persists across reload`(@TempDir dir: File) = runTest {
        SettingsRepository(JsonStore(dir)).set(AppSettings(syncOnMobileData = false))
        assertEquals(AppSettings(syncOnMobileData = false), SettingsRepository(JsonStore(dir)).get())
    }
}
