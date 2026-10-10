package io.unisondroid.app.service

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.data.Profile
import io.unisondroid.app.sync.SyncMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: SyncScheduler
    private lateinit var engine: StubSyncEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = SyncScheduler(context)
        ServiceLocator.reset()
        engine = StubSyncEngine()
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `enabling a profile enqueues periodic work with the mobile-data constraint`() {
        scheduler.reconcile(
            listOf(profile("p1", enabled = true, intervalMinutes = 60)),
            AppSettings(syncOnMobileData = true),
        )

        val info = onlyWork("sync-p1")
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertNotNull("periodic work must advertise its interval", info.periodicityInfo)
        assertEquals(60 * 60_000L, info.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun `disabling mobile data uses the unmetered constraint`() {
        scheduler.reconcile(
            listOf(profile("p1", enabled = true, intervalMinutes = 60)),
            AppSettings(syncOnMobileData = false),
        )

        assertEquals(NetworkType.UNMETERED, onlyWork("sync-p1").constraints.requiredNetworkType)
    }

    @Test
    fun `disabling a profile cancels its periodic work`() {
        scheduler.reconcile(listOf(profile("p1", enabled = true)), AppSettings())
        assertEquals(WorkInfo.State.ENQUEUED, onlyWork("sync-p1").state)

        scheduler.reconcile(listOf(profile("p1", enabled = false)), AppSettings())

        val info = onlyWork("sync-p1")
        assertEquals(WorkInfo.State.CANCELLED, info.state)
    }

    @Test
    fun `reconcile clamps intervals below 15 minutes`() {
        scheduler.reconcile(
            listOf(profile("p1", enabled = true, intervalMinutes = 5)),
            AppSettings(),
        )

        assertEquals(15 * 60_000L, onlyWork("sync-p1").periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun `cancel stops a profile's periodic work`() {
        scheduler.reconcile(listOf(profile("p1", enabled = true)), AppSettings())
        assertEquals(WorkInfo.State.ENQUEUED, onlyWork("sync-p1").state)

        scheduler.cancel("p1")

        assertEquals(WorkInfo.State.CANCELLED, onlyWork("sync-p1").state)
    }

    @Test
    fun `syncNow enqueues a one-shot INTERACTIVE request`() {
        scheduler.syncNow("p42")

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("sync-p42-now").get()
        assertEquals(1, infos.size)
        assertNull("manual sync must be one-shot", infos.first().periodicityInfo)

        awaitEngineRequest()
        assertEquals("p42", engine.requestedProfileId)
        assertEquals(SyncMode.INTERACTIVE, engine.requestedMode)
    }

    private fun onlyWork(name: String): WorkInfo =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().single()

    private fun awaitEngineRequest(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.requestedMode == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    private fun profile(
        id: String,
        enabled: Boolean = false,
        intervalMinutes: Int = 60,
    ): Profile = Profile(
        id = id,
        name = id,
        localRoot = "/tmp/local",
        remoteRoot = "/remote",
        host = "example.invalid",
        user = "user",
        sshKeyId = "key",
        autoSyncEnabled = enabled,
        autoSyncIntervalMinutes = intervalMinutes,
    )
}
