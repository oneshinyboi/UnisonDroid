package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeystoreAesCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.Transport
import io.unisondroid.app.sync.ProcessSshTool
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class E2eProvisioningTest {

    @Test
    fun provisionProfileAndKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = JsonStore(context.filesDir)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val tool = ProcessSshTool(
            keygen = File(nativeDir, "libssh-keygen.so"),
            keyscan = File(nativeDir, "libssh-keyscan.so"),
            workDir = context.cacheDir,
        )
        val vault = KeyVault(store, KeystoreAesCipher(), tool)
        val key = vault.keys().firstOrNull { it.name == KEY_NAME } ?: vault.generate(KEY_NAME)
        val profiles = ProfileRepository(store)
        profiles.save(
            Profile(
                id = PROFILE_ID,
                name = "veryshiny e2e",
                localRoot = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }.absolutePath,
                remoteRoot = "/home/Diamond/unisondroid-e2e",
                host = "veryshiny.net",
                sshPort = 22,
                user = "Diamond",
                sshKeyId = key.id,
                transport = Transport.SSH_EXEC,
                serverCommand = "unison",
            ),
        )
        File(context.filesDir, "provisioned_pubkey.txt").writeText(key.publicKey)
        println("E2EPROV-PROFILE-ID:$PROFILE_ID")
        println("E2EPROV-LOCAL-ROOT:${File(context.getExternalFilesDir(null), "e2e").absolutePath}")
        println("E2EPROV-PUBLIC-KEY:${key.publicKey}")
    }

    private companion object {
        const val KEY_NAME = "veryshiny-e2e"
        const val PROFILE_ID = "vsyne2e"
    }
}
