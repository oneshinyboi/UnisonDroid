package io.unisondroid.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.SyncVariant
import io.unisondroid.app.sync.UnisonInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val RUN_LOG_TAG = "run-log"
const val RUN_PROGRESS_TAG = "run-progress"
const val RUN_CANCEL_TAG = "run-cancel"
const val RUN_SUMMARY_TAG = "run-summary"
const val RUN_CONNECTION_OK_TAG = "run-connectionOk"
const val RUN_FAILURE_TAG = "run-failure"
const val RUN_ENGINE_MISSING_TAG = "run-engineMissing"
const val RUN_HOSTKEY_DIALOG_TAG = "run-hostKeyDialog"
const val RUN_CONFLICT_ROW = "run-conflict-row"
const val RUN_FAILED_ROW = "run-failed-row"
const val RUN_RESOLVE_TAG = "run-resolveConflicts"
const val RUN_RETRY_VERSION_TAG = "run-retryVersion"

@Composable
fun RunScreen(
    profileId: String,
    variant: SyncVariant = SyncVariant.TWO_WAY,
    confirmed: Boolean = false,
    onResolveConflicts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember(context) { ServiceLocator.engine(context) }
    val repository = remember(context) { ServiceLocator.profiles(context) }
    val currentVersion by produceState(
        initialValue = UnisonInfo.DEFAULT.version,
        profileId,
    ) {
        value = repository.get(profileId)?.unisonVersion?.ifBlank { UnisonInfo.DEFAULT.version }
            ?: UnisonInfo.DEFAULT.version
    }
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsState()
    var runKey by remember { mutableStateOf(0) }

    LaunchedEffect(profileId, variant, confirmed, runKey) {
        engine.beginRun()
        SyncScheduler(context).syncNow(profileId, variant, confirmed)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val requestStorageAccess = rememberAllFilesAccessRequest()
    RunContent(
        state = state,
        variant = variant,
        onCancel = { engine.cancel() },
        onHostKeyDecision = { approve -> scope.launch { engine.respondHostKey(approve) } },
        onGrantStorageAccess = requestStorageAccess,
        onResolveConflicts = onResolveConflicts,
        onRetryWithVersion = { version ->
            scope.launch {
                repository.get(profileId)?.let { repository.save(it.copy(unisonVersion = version)) }
                runKey++
            }
        },
        currentVersion = currentVersion,
        modifier = modifier,
    )}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunContent(
    state: SyncState,
    onCancel: () -> Unit,
    onHostKeyDecision: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onGrantStorageAccess: () -> Unit = {},
    onResolveConflicts: () -> Unit = {},
    onRetryWithVersion: (String) -> Unit = {},
    variant: SyncVariant = SyncVariant.TWO_WAY,
    currentVersion: String = UnisonInfo.DEFAULT.version,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = variant.title()) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            when (state) {
                SyncState.Idle ->
                    StatusLine(if (variant.isDiagnostic) "Testing connection…" else "Starting sync…")

                is SyncState.Connecting -> StatusLine("Connecting to the server…")
                is SyncState.Syncing -> SyncingBody(
                    state = state,
                    onCancel = onCancel,
                    label = if (variant.isDiagnostic) "Testing connection…" else "Syncing…",
                )
                is SyncState.Finished ->
                    if (variant.isDiagnostic) {
                        ConnectionOkCard()
                    } else {
                        SummaryCard(state, onResolveConflicts)
                        if (state.log.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FinishedLog(state.log)
                        }
                    }

                is SyncState.Failed ->
                    if (state.reason == SyncState.Reason.BINARY_MISSING) {
                        EngineMissingCard(state.detail)
                    } else {
                        FailureCard(
                            state = state,
                            onGrantStorageAccess = onGrantStorageAccess,
                            onRetryWithVersion = onRetryWithVersion,
                            currentVersion = currentVersion,
                            title = if (variant.isDiagnostic) "Connection test failed" else "Sync failed",
                        )
                    }

                is SyncState.AwaitingHostKey -> StatusLine("Waiting for host key approval…")
            }
        }
    }

    if (state is SyncState.AwaitingHostKey) {
        HostKeyDialog(fingerprint = state.fingerprint, onDecision = onHostKeyDecision)
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ColumnScope.SyncingBody(
    state: SyncState.Syncing,
    onCancel: () -> Unit,
    label: String = "Syncing…",
) {
    Text(
        text = "$label ${(state.progress * 100).roundToInt()}%",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(RUN_PROGRESS_TAG),
    )
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag(RUN_LOG_TAG),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.log.asReversed()) { line ->
            Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.testTag(RUN_CANCEL_TAG),
    ) {
        Text(text = "Cancel")
    }
}

@Composable
private fun ConnectionOkCard() {
    Card(modifier = Modifier.fillMaxWidth().testTag(RUN_CONNECTION_OK_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Connection OK", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Reached the server successfully. No files were changed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SummaryCard(state: SyncState.Finished, onResolveConflicts: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(RUN_SUMMARY_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Sync finished", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${state.summary.transferred} transferred",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.summary.conflicts.forEach { conflict ->
                Text(
                    text = detailLine(conflict.path, conflict.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(RUN_CONFLICT_ROW),
                )
            }
            state.summary.failed.forEach { failure ->
                Text(
                    text = detailLine(failure.path, failure.message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(RUN_FAILED_ROW),
                )
            }
            if (state.summary.conflicts.any { it.resolvable }) {
                OutlinedButton(
                    onClick = onResolveConflicts,
                    modifier = Modifier.testTag(RUN_RESOLVE_TAG),
                ) { Text(text = "Resolve conflicts") }
            }
        }
    }
}

private fun detailLine(path: String, detail: String): String =
    if (detail.isBlank()) path else "$path — $detail"

/**
 * The raw Unison output, kept on screen after the run finishes. Previously the log was only shown
 * while syncing, so a fast run (e.g. one that aborted on an archive error) hid the reason it did
 * nothing. Newest-first, matching the live view.
 */
@Composable
private fun ColumnScope.FinishedLog(log: List<String>) {
    Text(text = "Unison log", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag(RUN_LOG_TAG),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(log.asReversed()) { line ->
            Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FailureCard(
    state: SyncState.Failed,
    onGrantStorageAccess: () -> Unit,
    onRetryWithVersion: (String) -> Unit = {},
    currentVersion: String = UnisonInfo.DEFAULT.version,
    title: String = "Sync failed",
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(RUN_FAILURE_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(text = reasonLabel(state.reason), style = MaterialTheme.typography.bodyMedium)
            Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
            if (state.reason == SyncState.Reason.LOCAL_PERMISSIONS) {
                Text(
                    text = "Grant All Files Access so UnisonDroid can read and write your " +
                        "local folders, then run the sync again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onGrantStorageAccess,
                    modifier = Modifier.testTag(GRANT_ACCESS_TAG),
                ) { Text(text = "Grant All Files Access") }
            }
            if (state.reason == SyncState.Reason.VERSION) {
                Text(
                    text = "The server runs a different Unison version. Try another bundled version:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UnisonInfo.BUNDLED.filter { it.version != currentVersion }.forEach { bundled ->
                    OutlinedButton(
                        onClick = { onRetryWithVersion(bundled.version) },
                        modifier = Modifier.testTag(RUN_RETRY_VERSION_TAG),
                    ) { Text(text = "Try Unison ${bundled.version}") }
                }
            }
        }
    }
}

@Composable
private fun EngineMissingCard(detail: String) {
    Card(modifier = Modifier.fillMaxWidth().testTag(RUN_ENGINE_MISSING_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Unison engine missing",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "UnisonDroid ships the unison engine as a native library, but it was not " +
                    "found on this device. Reinstall the app, or install a build that includes " +
                    "the engine for your device's ABI.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun HostKeyDialog(fingerprint: String, onDecision: (Boolean) -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(RUN_HOSTKEY_DIALOG_TAG),
        onDismissRequest = {},
        title = { Text(text = "Unknown host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "The server presented a host key that is not yet trusted. Verify the " +
                        "fingerprint before approving it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text(text = "Approve") }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text(text = "Deny") }
        },
    )
}

private fun reasonLabel(reason: SyncState.Reason): String =
    reason.name.lowercase().replace('_', ' ')
