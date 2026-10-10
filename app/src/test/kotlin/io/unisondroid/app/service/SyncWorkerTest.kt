package io.unisondroid.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import io.unisondroid.app.sync.SyncMode
import io.unisondroid.app.sync.SyncOutcome
import io.unisondroid.app.sync.SyncVariant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var engine: StubSyncEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
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
    fun `unattended skip posts a needs-approval notification and succeeds`() {
        engine.outcome = SyncOutcome.SKIPPED_UNTRUSTED

        val result = runBlocking { worker("p1", SyncMode.UNATTENDED).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        val text = terminalNotificationText()
        assertNotNull("a needs-approval notification must be posted", text)
        assertTrue(text!!.contains("approval", ignoreCase = true))
    }

    @Test
    fun `busy result returns retry for unattended work`() {
        engine.outcome = SyncOutcome.BUSY

        val result = runBlocking { worker("p1", SyncMode.UNATTENDED).doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `busy result is a no-op for interactive work`() {
        engine.outcome = SyncOutcome.BUSY

        val result = runBlocking { worker("p1", SyncMode.INTERACTIVE).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `unattended failure posts a failure notification and returns failure`() {
        engine.outcome = SyncOutcome.FAILED

        val result = runBlocking { worker("p1", SyncMode.UNATTENDED).doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
        val text = terminalNotificationText()
        assertNotNull("unattended failures must be surfaced to the user", text)
        assertTrue(text!!.contains("failed", ignoreCase = true))
    }

    @Test
    fun `interactive failure returns failure without posting a terminal notification`() {
        engine.outcome = SyncOutcome.FAILED

        val result = runBlocking { worker("p1", SyncMode.INTERACTIVE).doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
        assertNull("interactive runs are surfaced in the UI, not notifications", terminalNotificationText())
    }

    @Test
    fun `completed returns success and passes the mode through`() {
        engine.outcome = SyncOutcome.COMPLETED

        val result = runBlocking { worker("p42", SyncMode.INTERACTIVE).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("p42", engine.requestedProfileId)
        assertEquals(SyncMode.INTERACTIVE, engine.requestedMode)
    }

    @Test
    fun `engine exception returns retry`() {
        engine.throwOnRequest = IllegalStateException("boom")

        val result = runBlocking { worker("p1", SyncMode.UNATTENDED).doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `engine exception returns failure for interactive work`() {
        engine.throwOnRequest = IllegalStateException("boom")

        val result = runBlocking { worker("p1", SyncMode.INTERACTIVE).doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `foreground failure does not crash the worker`() {
        engine.outcome = SyncOutcome.COMPLETED

        val built = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(input("p1", SyncMode.UNATTENDED))
            .setForegroundUpdater(object : ForegroundUpdater {
                override fun setForegroundAsync(
                    context: Context,
                    id: UUID,
                    foregroundInfo: ForegroundInfo,
                ): ListenableFuture<Void> {
                    throw IllegalStateException("foreground service start denied")
                }
            })
            .build()

        val result = runBlocking { built.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(SyncMode.UNATTENDED, engine.requestedMode)
    }

    @Test
    fun `worker forwards the run variant to the engine`() {
        engine.outcome = SyncOutcome.COMPLETED

        val result = runBlocking {
            worker("p42", SyncMode.INTERACTIVE, SyncVariant.COPY_FROM_SERVER).doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(SyncVariant.COPY_FROM_SERVER, engine.requestedVariant)
    }

    @Test
    fun `missing variant input defaults to two way`() {
        val built = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_PROFILE_ID to "p1",
                    SyncWorker.KEY_MODE to SyncMode.INTERACTIVE.name,
                ),
            )
            .build()

        runBlocking { built.doWork() }

        assertEquals(SyncVariant.TWO_WAY, engine.requestedVariant)
    }

    private fun worker(
        profileId: String,
        mode: SyncMode,
        variant: SyncVariant = SyncVariant.TWO_WAY,
    ): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(input(profileId, mode, variant))
            .build()

    private fun input(
        profileId: String,
        mode: SyncMode,
        variant: SyncVariant = SyncVariant.TWO_WAY,
    ) = workDataOf(
        SyncWorker.KEY_PROFILE_ID to profileId,
        SyncWorker.KEY_MODE to mode.name,
        SyncWorker.KEY_VARIANT to variant.name,
    )

    private fun terminalNotificationText(): String? =
        shadowOf(notificationManager())
            .getNotification(SyncNotifications.TERMINAL_NOTIFICATION_ID)
            ?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
