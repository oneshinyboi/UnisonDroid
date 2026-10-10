package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.data.Profile
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.SyncMode
import io.unisondroid.app.sync.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-server end-to-end over the bundled OpenSSH transport. Skipped unless the
 * caller supplies instrumentation args:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.E2E_HOST=veryshiny.net \
 *   -Pandroid.testInstrumentationRunnerArguments.E2E_USER=Diamond \
 *   -Pandroid.testInstrumentationRunnerArguments.E2E_REMOTE_ROOT=/home/Diamond/unisondroid-e2e \
 *   [-Pandroid.testInstrumentationRunnerArguments.E2E_KEY=veryshiny-e2e]
 *
 * The named key must already be in the app's KeyVault and authorised in the
 * server's authorized_keys (run E2eProvisioningTest and authorize its public
 * key first).
 */
@RunWith(AndroidJUnit4::class)
class SshE2eTest {

    @Test
    fun syncsToTheRealServerOverBundledSsh() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("E2E_HOST")
        val user = args.getString("E2E_USER")
        val remoteRoot = args.getString("E2E_REMOTE_ROOT")
        assumeTrue(
            "set E2E_HOST, E2E_USER and E2E_REMOTE_ROOT to run this test",
            host != null && user != null && remoteRoot != null,
        )
        val keyName = args.getString("E2E_KEY") ?: "veryshiny-e2e"

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.reset()
        val key = ServiceLocator.keys(context).keys().firstOrNull { it.name == keyName }
        assumeTrue("no key named '$keyName'; provision and authorise one first", key != null)

        val localDir = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        File(localDir, "hello-from-device.txt").writeText("hello ${System.currentTimeMillis()}\n")

        val repo = ServiceLocator.profiles(context)
        val profile = Profile(
            id = "sshe2e",
            name = "ssh e2e",
            localRoot = localDir.absolutePath,
            remoteRoot = remoteRoot!!,
            host = host!!,
            sshPort = 22,
            user = user!!,
            sshKeyId = key!!.id,
        )
        repo.save(profile)

        val engine = ServiceLocator.engine(context)
        val job = launch { engine.requestSync(profile.id, SyncMode.INTERACTIVE) }

        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            when (engine.state.value) {
                is SyncState.AwaitingHostKey -> engine.respondHostKey(true)
                is SyncState.Finished, is SyncState.Failed -> break
                else -> delay(200)
            }
        }
        job.join()

        val state = engine.state.value
        assertTrue("expected Finished, got: $state", state is SyncState.Finished)
    }
}
