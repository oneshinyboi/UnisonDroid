package io.unisondroid.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.unisondroid.app.ui.components.StatusChip
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val GETTING_STARTED_TAG = "gettingStartedCard"
const val PROFILE_ROW_TAG = "profileRow"
const val LAST_SYNC_TAG = "lastSync"
const val PROFILES_KEYS_ACTION_TAG = "profiles-keys-action"
const val PROFILES_ABOUT_ACTION_TAG = "profiles-about-action"
const val PROFILE_EDIT_PREFIX = "profileEdit-"
const val PROFILE_DELETE_PREFIX = "profileDelete-"
const val DELETE_CONFIRM_TAG = "confirmDeleteProfile"

private val LAST_SYNC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

internal fun lastSyncLabel(at: Long?): String =
    if (at == null) "Never synced" else "Last sync: " + LAST_SYNC_FORMATTER.format(Instant.ofEpochMilli(at))

@Composable
fun ProfilesScreen(
    onOpenProfile: (String?) -> Unit,
    onStartSync: (String) -> Unit,
    onOpenKeys: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
    ProfilesContent(
        profiles = profiles,
        onOpenProfile = onOpenProfile,
        onStartSync = onStartSync,
        hasLocalAccess = hasAccess,
        onRequestAccess = requestAccess,
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
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "UnisonDroid") },
                actions = {
                    TextButton(
                        onClick = onOpenKeys,
                        modifier = Modifier.testTag(PROFILES_KEYS_ACTION_TAG),
                    ) { Text("Keys") }
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
                        onClick = { onStartSync(profile.id) },
                        onEdit = { onOpenProfile(profile.id) },
                        onDelete = { pendingDelete = profile },
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
}

@Composable
private fun ProfileRow(
    profile: Profile,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(result = profile.lastResult)
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
