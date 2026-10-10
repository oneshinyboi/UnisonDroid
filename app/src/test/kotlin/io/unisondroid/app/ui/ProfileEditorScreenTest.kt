package io.unisondroid.app.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.ConflictPolicy
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.Transport
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var dir: File
    private lateinit var repository: ProfileRepository
    private lateinit var vault: KeyVault

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        dir = Files.createTempDirectory("profile-editor-test").toFile()
        val store = JsonStore(dir)
        repository = ProfileRepository(store)
        vault = KeyVault(store, XorCipher, FakeSshTool())
        runBlocking { vault.generate("laptop") }
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { repository }
        ServiceLocator.keysProvider = { vault }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.keysProvider = ServiceLocator.defaultKeysProvider
        ServiceLocator.reset()
    }

    @Test
    fun `validation rejects save when required fields are missing`() {
        var saved = false
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = { saved = true })
            }
        }
        awaitEditor()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.onNodeWithTag(VALIDATION_ERROR).assertExists()
        assertFalse(saved)
        assertTrue(runBlocking { repository.profiles() }.isEmpty())
    }

    @Test
    fun `advanced text and ignore patterns round-trip into the saved profile`() {
        var saved = false
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = { saved = true })
            }
        }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(FIELD_IGNORE).performScrollTo().performTextInput("*.tmp\nbuild/")
        compose.onNodeWithTag(FIELD_ADVANCED).performScrollTo().performTextInput("-batch")

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        val profile = awaitSavedProfile()
        assertEquals("-batch", profile.advancedPrefs)
        assertEquals(listOf("*.tmp", "build/"), profile.ignorePatterns)
        compose.waitUntil(5_000) { saved }
        assertTrue(saved)
    }

    @Test
    fun `saved profile persists via the repository`() {
        var saved = false
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = { saved = true })
            }
        }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        val profile = awaitSavedProfile()
        assertEquals("Phone", profile.name)
        assertEquals("/storage/emulated/0/Unison", profile.localRoot)
        assertEquals("/home/user/sync", profile.remoteRoot)
        assertEquals("server.example.com", profile.host)
        assertEquals("user", profile.user)

        val reloaded = runBlocking { ProfileRepository(JsonStore(dir)).profiles() }
        assertEquals(listOf(profile), reloaded)
    }

    @Test
    fun `save is rejected when no ssh key is available`() {
        val emptyVault = KeyVault(
            JsonStore(Files.createTempDirectory("empty-vault").toFile()),
            XorCipher,
            FakeSshTool(),
        )
        ServiceLocator.keysProvider = { emptyVault }
        var saved = false
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = { saved = true })
            }
        }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.onNodeWithTag(VALIDATION_ERROR).assertExists()
        assertFalse(saved)
        assertTrue(runBlocking { repository.profiles() }.isEmpty())
    }

    @Test
    fun `browse without storage access shows the grant prompt not an empty browser`() {
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorContent(
                    initial = null,
                    keys = emptyList(),
                    onSave = {},
                    hasLocalAccess = false,
                )
            }
        }

        compose.onNodeWithTag(BROWSE_LOCAL).performScrollTo().performClick()

        compose.onNodeWithTag(GRANT_ACCESS_TAG).assertExists()
        compose.onNodeWithTag(PATH_CWD).assertDoesNotExist()
    }

    @Test
    fun `path browser lists children and returns the picked absolute path`() {
        val root = Files.createTempDirectory("path-browser-test").toFile()
        val folder = File(root, "aFolder").apply { mkdirs() }
        File(root, "bFile.txt").writeText("x")
        var picked: String? = null

        compose.setContent {
            UnisonDroidTheme {
                PathBrowser(startDir = root, onPicked = { picked = it })
            }
        }

        compose.onNodeWithText("aFolder").assertExists()
        compose.onNodeWithText("bFile.txt").assertExists()

        compose.onNodeWithText("aFolder").performClick()
        compose.onNodeWithTag(PATH_PICK).performClick()

        assertEquals(folder.absolutePath, picked)
    }

    @Test
    fun `new profiles default to the SSH exec transport`() {
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = {})
            }
        }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        val profile = awaitSavedProfile()
        assertEquals(Transport.SSH_EXEC, profile.transport)
        assertEquals("unison", profile.serverCommand)
    }

    @Test
    fun `auto-sync switch and interval round-trip into the saved profile`() {
        runBlocking { repository.save(seedProfile(autoSyncEnabled = true, autoSyncIntervalMinutes = 180)) }

        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = "p1", onSaved = {})
            }
        }
        awaitEditor()

        compose.onNodeWithTag(FIELD_AUTO_SYNC).performScrollTo().assertIsOn()
        compose.onNodeWithTag(FIELD_INTERVAL).performScrollTo()
        compose.onNodeWithText("3 h").assertExists()

        compose.onNodeWithTag(FIELD_AUTO_SYNC).performScrollTo().performClick()
        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.waitUntil(5_000) {
            runBlocking { repository.get("p1")?.autoSyncEnabled == false }
        }
        assertFalse(runBlocking { repository.get("p1")!!.autoSyncEnabled })
        assertEquals(
            "the interval must round-trip even after the switch is turned off",
            180,
            runBlocking { repository.get("p1")!!.autoSyncIntervalMinutes },
        )
    }

    @Test
    fun `auto-sync switch notifies only when it is turned on`() {
        var enabledCount = 0
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorContent(
                    initial = null,
                    keys = emptyList(),
                    onSave = {},
                    onAutoSyncEnabled = { enabledCount++ },
                )
            }
        }

        compose.onNodeWithTag(FIELD_AUTO_SYNC).performScrollTo().assertIsOff()

        compose.onNodeWithTag(FIELD_AUTO_SYNC).performScrollTo().performClick()
        compose.onNodeWithTag(FIELD_AUTO_SYNC).assertIsOn()
        assertEquals("turning auto-sync on must trigger the permission flow once", 1, enabledCount)

        compose.onNodeWithTag(FIELD_AUTO_SYNC).performScrollTo().performClick()
        compose.onNodeWithTag(FIELD_AUTO_SYNC).assertIsOff()
        assertEquals("turning auto-sync off must not trigger the permission flow", 1, enabledCount)
    }

    @Test
    fun `new profiles default the conflict policy to skip`() {
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = {})
            }
        }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        val profile = awaitSavedProfile()
        assertEquals(ConflictPolicy.SKIP, profile.conflictPolicy)
    }

    @Test
    fun `existing conflict policy is shown and preserved on save`() {
        runBlocking { repository.save(seedProfile(conflictPolicy = ConflictPolicy.PREFER_NEWER)) }

        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = "p1", onSaved = {})
            }
        }
        awaitEditor()

        compose.onNodeWithTag(FIELD_CONFLICT_POLICY).performScrollTo()
        compose.onNodeWithText("Prefer newer").assertExists()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.waitUntil(5_000) {
            runBlocking { repository.get("p1")?.conflictPolicy == ConflictPolicy.PREFER_NEWER }
        }
        assertEquals(
            "the seeded policy must be carried into the saved profile",
            ConflictPolicy.PREFER_NEWER,
            runBlocking { repository.get("p1")!!.conflictPolicy },
        )
    }

    @Test
    fun `conflict policy selection round-trips into the saved profile`() {
        runBlocking { repository.save(seedProfile(conflictPolicy = ConflictPolicy.PREFER_NEWER)) }

        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = "p1", onSaved = {})
            }
        }
        awaitEditor()

        compose.onNodeWithTag(FIELD_CONFLICT_POLICY).performScrollTo().performClick()
        compose.onNodeWithText("Keep both copies").performClick()
        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.waitUntil(5_000) {
            runBlocking { repository.get("p1")?.conflictPolicy == ConflictPolicy.KEEP_BOTH }
        }
        assertEquals(ConflictPolicy.KEEP_BOTH, runBlocking { repository.get("p1")!!.conflictPolicy })
    }

    @Test
    fun `prefer older shows the file-times hint`() {
        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = null, onSaved = {})
            }
        }
        awaitEditor()

        compose.onNodeWithTag(FIELD_CONFLICT_POLICY).performScrollTo().performClick()
        compose.onNodeWithText("Prefer older").performClick()

        compose.onNodeWithText("Prefer older requires syncing file times.").assertExists()
    }

    @Test
    fun `editing a profile preserves its stored conflicts`() {
        val conflicts = listOf(ConflictRecord("a.txt", "conflicting updates"))
        runBlocking { repository.save(seedProfile(lastConflicts = conflicts)) }

        compose.setContent {
            UnisonDroidTheme {
                ProfileEditorScreen(profileId = "p1", onSaved = {})
            }
        }
        awaitEditor()

        compose.onNodeWithTag(FIELD_CONFLICT_POLICY).performScrollTo().performClick()
        compose.onNodeWithText("Keep both copies").performClick()
        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        compose.waitUntil(5_000) {
            runBlocking { repository.get("p1")?.conflictPolicy == ConflictPolicy.KEEP_BOTH }
        }
        assertEquals(
            "editing must not wipe stored conflict records",
            conflicts,
            runBlocking { repository.get("p1")!!.lastConflicts },
        )
    }

    private fun fillRequired() {
        compose.onNodeWithTag(FIELD_NAME).performScrollTo().performTextInput("Phone")
        compose.onNodeWithTag(FIELD_LOCAL_ROOT).performScrollTo().performTextInput("/storage/emulated/0/Unison")
        compose.onNodeWithTag(FIELD_REMOTE_ROOT).performScrollTo().performTextInput("/home/user/sync")
        compose.onNodeWithTag(FIELD_HOST).performScrollTo().performTextInput("server.example.com")
        compose.onNodeWithTag(FIELD_USER).performScrollTo().performTextInput("user")
    }

    private fun awaitEditor() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(FIELD_NAME).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitSavedProfile(): Profile {
        compose.waitUntil(5_000) {
            runBlocking { repository.profiles() }.isNotEmpty()
        }
        return runBlocking { repository.profiles() }.single()
    }

    private fun seedProfile(
        autoSyncEnabled: Boolean = false,
        autoSyncIntervalMinutes: Int = 60,
        conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
        lastConflicts: List<ConflictRecord> = emptyList(),
    ): Profile = Profile(
        id = "p1",
        name = "Phone",
        localRoot = "/storage/emulated/0/Unison",
        remoteRoot = "/home/user/sync",
        host = "server.example.com",
        user = "user",
        sshKeyId = "key1",
        autoSyncEnabled = autoSyncEnabled,
        autoSyncIntervalMinutes = autoSyncIntervalMinutes,
        conflictPolicy = conflictPolicy,
        lastConflicts = lastConflicts,
    )

    private object XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }
}
