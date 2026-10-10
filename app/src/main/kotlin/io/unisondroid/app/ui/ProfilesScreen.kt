package io.unisondroid.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import io.unisondroid.app.data.Profile
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.SyncVariant
import io.unisondroid.app.ui.components.StatusChip
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val GETTING_STARTED_TAG = "gettingStartedCard"
const val PROFILE_ROW_TAG = "profileRow"
const val LAST_SYNC_TAG = "lastSync"
const val PROFILES_KEYS_ACTION_TAG = "profiles-keys-action"
const val PROFILES_ABOUT_ACTION_TAG = "profiles-about-action"
const val PROFILES_SETTINGS_ACTION_TAG = "profiles-settings-action"
const val PROFILE_EDIT_PREFIX = "profileEdit-"
const val PROFILE_DELETE_PREFIX = "profileDelete-"
const val PROFILE_RESOLVE_PREFIX = "profileResolve-"
const val CONFLICTS_COUNT_PREFIX = "profileConflicts-"
const val DELETE_CONFIRM_TAG = "confirmDeleteProfile"
const val PROFILE_ACTION_PREFIX = "profileAction-"
const val MIRROR_CONFIRM_TAG = "confirmMirrorRun"
const val SYNC_BUSY_MESSAGE = "A sync is already running. Try again when it finishes."

private val LAST_SYNC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal fun lastSyncLabel(at: Long?): String =
    if (at == null) "Never synced" else "Last sync: " + LAST_SYNC_FORMATTER.format(Instant.ofEpochMilli(at))

@Composable
fun ProfilesScreen(
    onOpenProfile: (String?) -> Unit,
    onStartSync: (String) -> Unit,
    onOpenKeys: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onResolveConflicts: (String) -> Unit = {},
    onRunVariant: (String, SyncVariant, Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { ServiceLocator.profiles(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val profiles by produceState<List<Profile>?>(initialValue = null, repository, refresh) {
        value = repository.profiles()
    }
    var hasAccess by remember { mutableStateOf(hasAllFilesAccess(context)) }
    val requestAccess = rememberAllFilesAccessRequest { hasAccess = it }
    val engine = remember(context) { ServiceLocator.engine(context) }
    val syncState by engine.state.collectAsState()
    val runPending by engine.runPending.collectAsState()
    ProfilesContent(
        profiles = profiles,
        onOpenProfile = onOpenProfile,
        onStartSync = onStartSync,
        hasLocalAccess = hasAccess,
        onRequestAccess = requestAccess,
        syncActive = syncState.isActiveRun() || runPending,
        onDeleteProfile = { id ->
            scope.launch {
                repository.delete(id)
                SyncScheduler(context).cancel(id)
                SyncScheduler(context).reconcile(
                    repository.profiles(),
                    ServiceLocator.settings(context).get(),
                )
                refresh++
            }
        },
        onOpenKeys = onOpenKeys,
        onOpenAbout = onOpenAbout,
        onOpenSettings = onOpenSettings,
        onResolveConflicts = onResolveConflicts,
        onRunVariant = onRunVariant,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesContent(
    profiles: List<Profile>?,
    onOpenProfile: (String?) -> Unit,
    onStartSync: (String) -> Unit,
    hasLocalAccess: Boolean = true,
    onRequestAccess: () -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onOpenKeys: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onResolveConflicts: (String) -> Unit = {},
    onRunVariant: (String, SyncVariant, Boolean) -> Unit = { _, _, _ -> },
    syncActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }
    var pendingMirror by remember { mutableStateOf<Pair<Profile, SyncVariant>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A second run enqueued while one is active is silently dropped, so tell the user instead.
    val warnBusy: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(SYNC_BUSY_MESSAGE)
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = "UnisonDroid") },
                actions = {
                    TextButton(
                        onClick = onOpenKeys,
                        modifier = Modifier.testTag(PROFILES_KEYS_ACTION_TAG),
                    ) { Text("Keys") }
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(PROFILES_SETTINGS_ACTION_TAG),
                    ) { Text("Settings") }
                    TextButton(
                        onClick = onOpenAbout,
                        modifier = Modifier.testTag(PROFILES_ABOUT_ACTION_TAG),
                    ) { Text("About") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenProfile(null) }) {
                Text(text = "New profile")
            }
        },
    ) { padding ->
        when {
            profiles == null -> Box(modifier = Modifier.padding(padding).fillMaxSize())
            profiles.isEmpty() -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                GettingStartedCard(modifier = Modifier.padding(16.dp))
                if (!hasLocalAccess) {
                    AllFilesAccessCard(
                        onGrant = onRequestAccess,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        onClick = { if (syncActive) warnBusy() else onStartSync(profile.id) },
                        onRunVariant = { variant ->
                            when {
                                syncActive -> warnBusy()
                                variant.destroysTarget -> pendingMirror = profile to variant
                                else -> onRunVariant(profile.id, variant, false)
                            }
                        },
                        onEdit = { onOpenProfile(profile.id) },
                        onDelete = { pendingDelete = profile },
                        onResolve = { onResolveConflicts(profile.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete profile?") },
            text = { Text("Delete \"${profile.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile.id)
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag(DELETE_CONFIRM_TAG),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingMirror?.let { (profile, variant) ->
        AlertDialog(
            onDismissRequest = { pendingMirror = null },
            title = { Text(mirrorTitle(variant)) },
            text = { Text(mirrorWarning(profile, variant)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (syncActive) {
                            warnBusy()
                        } else {
                            onRunVariant(profile.id, variant, true)
                        }
                        pendingMirror = null
                    },
                    modifier = Modifier.testTag(MIRROR_CONFIRM_TAG),
                ) { Text(mirrorTitle(variant).removeSuffix("?")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMirror = null }) { Text("Cancel") }
            },
        )
    }
}

/** A run is active (and a second run would be dropped) while the engine is in one of these states. */
private fun SyncState.isActiveRun(): Boolean =
    this is SyncState.Connecting || this is SyncState.Syncing || this is SyncState.AwaitingHostKey

private fun mirrorTitle(variant: SyncVariant): String =
    if (variant == SyncVariant.MIRROR_TO_SERVER) "Mirror to server?" else "Mirror from server?"

private fun mirrorWarning(profile: Profile, variant: SyncVariant): String =
    if (variant == SyncVariant.MIRROR_TO_SERVER) {
        "This makes the server an exact copy of this phone's folder (${profile.localRoot}). " +
            "Files on the server that are not here will be permanently deleted."
    } else {
        "This makes this phone an exact copy of the server folder (${profile.remoteRoot}). " +
            "Local files that are not on the server will be permanently deleted."
    }

@Composable
private fun ProfileRow(
    profile: Profile,
    onClick: () -> Unit,
    onRunVariant: (SyncVariant) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResolve: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                )
                .testTag("$PROFILE_ROW_TAG-${profile.id}"),
            headlineContent = { Text(text = profile.name) },
            supportingContent = {
                Column {
                    Text(text = profile.host, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = lastSyncLabel(profile.lastSyncedAt),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(LAST_SYNC_TAG),
                    )
                    if (profile.lastConflicts.any { it.resolvable }) {
                        Text(
                            text = "${profile.lastConflicts.count { it.resolvable }} conflicts",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("$CONFLICTS_COUNT_PREFIX${profile.id}"),
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(result = profile.lastResult)
                    if (profile.lastConflicts.any { it.resolvable }) {
                        TextButton(
                            onClick = onResolve,
                            modifier = Modifier.testTag("$PROFILE_RESOLVE_PREFIX${profile.id}"),
                        ) { Text("Resolve") }
                    }
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("$PROFILE_EDIT_PREFIX${profile.id}"),
                    ) { Text("Edit") }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("$PROFILE_DELETE_PREFIX${profile.id}"),
                    ) { Text("Delete") }
                }
            },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            RunMenuItem(profile, SyncVariant.TWO_WAY, SyncVariant.TWO_WAY.menuLabel()) {
                menuExpanded = false
                onRunVariant(it)
            }
            HorizontalDivider()
            RunMenuHeader("Upload to server")
            RunMenuItem(profile, SyncVariant.COPY_TO_SERVER, SyncVariant.COPY_TO_SERVER.menuLabel()) {
                menuExpanded = false
                onRunVariant(it)
            }
            RunMenuItem(profile, SyncVariant.MIRROR_TO_SERVER, SyncVariant.MIRROR_TO_SERVER.menuLabel(), destructive = true) {
                menuExpanded = false
                onRunVariant(it)
            }
            HorizontalDivider()
            RunMenuHeader("Download from server")
            RunMenuItem(profile, SyncVariant.COPY_FROM_SERVER, SyncVariant.COPY_FROM_SERVER.menuLabel()) {
                menuExpanded = false
                onRunVariant(it)
            }
            RunMenuItem(profile, SyncVariant.MIRROR_FROM_SERVER, SyncVariant.MIRROR_FROM_SERVER.menuLabel(), destructive = true) {
                menuExpanded = false
                onRunVariant(it)
            }
            HorizontalDivider()
            RunMenuHeader("Diagnostics")
            RunMenuItem(profile, SyncVariant.TEST_CONNECTION, SyncVariant.TEST_CONNECTION.menuLabel()) {
                menuExpanded = false
                onRunVariant(it)
            }
        }
    }
}

@Composable
private fun RunMenuItem(
    profile: Profile,
    variant: SyncVariant,
    label: String,
    destructive: Boolean = false,
    onClick: (SyncVariant) -> Unit,
) {
    DropdownMenuItem(
        text = {
            if (destructive) {
                Text(text = label, color = MaterialTheme.colorScheme.error)
            } else {
                Text(text = label)
            }
        },
        onClick = { onClick(variant) },
        modifier = Modifier.testTag("$PROFILE_ACTION_PREFIX${profile.id}-${variant.name}"),
    )
}

@Composable
private fun RunMenuHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun GettingStartedCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.testTag(GETTING_STARTED_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Server setup", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Before your first sync, set up Unison on the computer you want to " +
                    "sync with. UnisonDroid connects over SSH to a unison socket running there.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "1. Install unison and an SSH server on the computer.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "2. Start the socket: unison -socket 22333",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "3. Add your public key to ~/.ssh/authorized_keys — create one on the Keys screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "See docs/SERVER-SETUP.md for the full walkthrough.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
