package io.unisondroid.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SettingsRepository
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val store = JsonStore(Files.createTempDirectory("settings-screen-test").toFile())
        settings = SettingsRepository(store)
        profiles = ProfileRepository(store)
        ServiceLocator.reset()
        ServiceLocator.settingsProvider = { settings }
        ServiceLocator.profilesProvider = { profiles }
    }

    @After
    fun tearDown() {
        ServiceLocator.settingsProvider = ServiceLocator.defaultSettingsProvider
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.reset()
    }

    @Test
    fun `the dont-sync-on-mobile-data switch off by default and toggling reports it on`() {
        var toggled: Boolean? = null

        compose.setContent {
            UnisonDroidTheme {
                SettingsContent(dontSyncOnMobileData = false, onToggle = { toggled = it })
            }
        }

        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun `turning dont-sync-on-mobile-data on persists mobile data off and unmetered constraint`() {
        runBlocking {
            // syncOnMobileData = false means automatic syncs already wait for unmetered:
            // the label's switch must therefore render ON.
            settings.set(AppSettings(syncOnMobileData = false))
            profiles.save(autoSyncProfile("p1"))
        }

        compose.setContent {
            UnisonDroidTheme { SettingsScreen() }
        }
        awaitSwitchOn()

        // Turn the label OFF: mobile data becomes allowed.
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertIsOff()
        awaitStored(syncOnMobileData = true)

        // Turn the label ON: automatic syncs must wait for an unmetered connection.
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertIsOn()

        compose.waitUntil(5_000) {
            runBlocking { settings.get().syncOnMobileData == false }
        }
        assertEquals(
            "turning \"don't sync on mobile data\" on must persist syncOnMobileData = false",
            false,
            runBlocking { settings.get().syncOnMobileData },
        )

        compose.waitUntil(5_000) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName("p1"))
                .get()
                .any { it.constraints.requiredNetworkType == NetworkType.UNMETERED }
        }
        assertEquals(
            NetworkType.UNMETERED,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName("p1"))
                .get()
                .single()
                .constraints
                .requiredNetworkType,
        )
    }

    private fun awaitSwitchOn() {
        compose.waitUntil(5_000) {
            try {
                compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertIsOn()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    private fun awaitStored(syncOnMobileData: Boolean) {
        compose.waitUntil(5_000) {
            runBlocking { settings.get().syncOnMobileData == syncOnMobileData }
        }
    }

    private fun autoSyncProfile(id: String): Profile = Profile(
        id = id,
        name = id,
        localRoot = "/tmp/local",
        remoteRoot = "/remote",
        host = "example.invalid",
        user = "user",
        sshKeyId = "key",
        autoSyncEnabled = true,
        autoSyncIntervalMinutes = 60,
    )
}
