package io.unisondroid.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.unisondroid.app.data.Profile
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.ui.components.StatusChip
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val GETTING_STARTED_TAG = "gettingStartedCard"
const val PROFILE_ROW_TAG = "profileRow"
const val LAST_SYNC_TAG = "lastSync"
const val PROFILES_KEYS_ACTION_TAG = "profiles-keys-action"
const val PROFILES_ABOUT_ACTION_TAG = "profiles-about-action"

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
    val profiles by produceState<List<Profile>?>(initialValue = null, repository) {
        value = repository.profiles()
    }
    ProfilesContent(
        profiles = profiles,
        onOpenProfile = onOpenProfile,
        onStartSync = onStartSync,
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
    onOpenKeys: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
            profiles.isEmpty() -> GettingStartedCard(
                modifier = Modifier.padding(padding).padding(16.dp),
            )

            else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(profile = profile, onClick = { onStartSync(profile.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, onClick: () -> Unit) {
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
        trailingContent = { StatusChip(result = profile.lastResult) },
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
