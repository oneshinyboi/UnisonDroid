package io.unisondroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.SideInfo
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.Resolution
import io.unisondroid.app.sync.SyncOutcome
import io.unisondroid.app.sync.SyncState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

const val RESOLVE_ROW_PREFIX = "resolve-row-"
const val RESOLVE_SYNC_TAG = "resolve-sync"
const val RESOLVE_BUSY_TAG = "resolve-busy"
const val RESOLVE_FAILURE_TAG = "resolve-failure"
const val RESOLVE_KEEP_LOCAL = "resolve-keepLocal"
const val RESOLVE_KEEP_REMOTE = "resolve-keepRemote"
const val RESOLVE_KEEP_BOTH = "resolve-keepBoth"
const val RESOLVE_SKIP = "resolve-skip"

private val SIDE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal fun sideMetadataLabel(label: String, side: SideInfo?): String {
    if (side == null) return "$label: no metadata"
    val parts = buildList {
        side.kind?.takeIf { it.isNotBlank() }?.let { add(it) }
        side.sizeBytes?.let { add("$it bytes") }
        side.modifiedAt?.let { add(SIDE_TIME_FORMATTER.format(Instant.ofEpochMilli(it))) }
    }
    return if (parts.isEmpty()) "$label: no metadata" else "$label: " + parts.joinToString(", ")
}

@Composable
fun ResolveConflictsScreen(
    profileId: String,
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember(context) { ServiceLocator.engine(context) }
    val repository = remember(context) { ServiceLocator.profiles(context) }
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsState()

    var refresh by remember { mutableIntStateOf(0) }
    val profile by produceState<Profile?>(initialValue = null, repository, profileId, refresh) {
        value = repository.get(profileId)
    }

    var decisions by remember { mutableStateOf<Map<String, Resolution>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }

    ResolveConflictsContent(
        profile = profile,
        state = state,
        running = running,
        outcome = outcome,
        decisions = decisions,
        onDecision = { path, resolution -> decisions = decisions + (path to resolution) },
        onSync = {
            val current = decisions
            running = true
            outcome = null
            scope.launch {
                try {
                    // The engine re-parses the fresh output and rewrites lastConflicts, so
                    // reloading the profile drops paths that were resolved and keeps the rest.
                    outcome = engine.resolveConflicts(profileId, current)
                    decisions = emptyMap()
                    refresh++
                } finally {
                    running = false
                }
            }
        },
        onDone = onDone,
        onHostKeyDecision = { approve -> scope.launch { engine.respondHostKey(approve) } },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResolveConflictsContent(
    profile: Profile?,
    state: SyncState,
    running: Boolean,
    outcome: SyncOutcome?,
    decisions: Map<String, Resolution>,
    onDecision: (String, Resolution) -> Unit,
    onSync: () -> Unit,
    onDone: () -> Unit,
    onHostKeyDecision: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "Resolve conflicts") },
                actions = { TextButton(onClick = onDone) { Text("Done") } },
            )
        },
    ) { padding ->
        val conflicts = profile?.lastConflicts.orEmpty()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                profile == null -> Text(text = "Loading…", style = MaterialTheme.typography.bodyLarge)

                conflicts.isEmpty() -> Text(
                    text = "No conflicts to resolve.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> conflicts.forEach { conflict ->
                    ConflictRow(
                        conflict = conflict,
                        selected = decisions[conflict.path],
                        onDecision = { onDecision(conflict.path, it) },
                    )
                }
            }

            if (running) ResolveProgress(state)

            if (!running && outcome != null) {
                when {
                    outcome == SyncOutcome.BUSY -> Text(
                        text = "A sync is already running. Try again when it finishes.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(RESOLVE_BUSY_TAG),
                    )
                    state is SyncState.Failed -> ResolveFailure(state)
                }
            }

            if (conflicts.isNotEmpty()) {
                Button(
                    onClick = onSync,
                    enabled = !running,
                    modifier = Modifier.testTag(RESOLVE_SYNC_TAG),
                ) { Text(text = "Sync these choices") }
            }
        }
    }

    if (state is SyncState.AwaitingHostKey) {
        HostKeyDialog(fingerprint = state.fingerprint, onDecision = onHostKeyDecision)
    }
}

@Composable
private fun ConflictRow(
    conflict: ConflictRecord,
    selected: Resolution?,
    onDecision: (Resolution) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("$RESOLVE_ROW_PREFIX${conflict.path}")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = conflict.path, style = MaterialTheme.typography.titleMedium)
            if (conflict.reason.isNotBlank()) {
                Text(text = conflict.reason, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = sideMetadataLabel("Phone", conflict.local),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = sideMetadataLabel("Server", conflict.remote),
                style = MaterialTheme.typography.bodySmall,
            )
            if (conflict.resolvable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChoiceChip("Keep phone", RESOLVE_KEEP_LOCAL, Resolution.KEEP_LOCAL, selected, conflict, onDecision)
                    ChoiceChip("Keep server", RESOLVE_KEEP_REMOTE, Resolution.KEEP_REMOTE, selected, conflict, onDecision)
                    ChoiceChip("Keep both", RESOLVE_KEEP_BOTH, Resolution.KEEP_BOTH, selected, conflict, onDecision)
                    ChoiceChip("Skip", RESOLVE_SKIP, Resolution.SKIP, selected, conflict, onDecision)
                }
            } else {
                Text(
                    text = "This conflict cannot resolve automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    tag: String,
    resolution: Resolution,
    selected: Resolution?,
    conflict: ConflictRecord,
    onDecision: (Resolution) -> Unit,
) {
    FilterChip(
        selected = selected == resolution,
        onClick = { onDecision(resolution) },
        label = { Text(text = label) },
        modifier = Modifier.testTag("$tag-${conflict.path}"),
    )
}

@Composable
private fun ResolveProgress(state: SyncState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is SyncState.Syncing -> {
                Text(text = "Syncing… ${(state.progress * 100).roundToInt()}%")
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SyncState.Connecting -> Text(text = "Connecting to the server…")
            is SyncState.AwaitingHostKey -> Text(text = "Waiting for host key approval…")
            else -> Text(text = "Working…")
        }
    }
}

@Composable
private fun ResolveFailure(state: SyncState.Failed) {
    Text(
        text = "Could not resolve conflicts (${state.reason.name}): ${state.detail}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(RESOLVE_FAILURE_TAG),
    )
}
