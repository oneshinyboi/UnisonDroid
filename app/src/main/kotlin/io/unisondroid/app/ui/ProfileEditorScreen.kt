package io.unisondroid.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.SshKey
import io.unisondroid.app.data.Transport
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import kotlinx.coroutines.launch
import java.io.File

const val FIELD_NAME = "editor-name"
const val FIELD_LOCAL_ROOT = "editor-localRoot"
const val FIELD_REMOTE_ROOT = "editor-remoteRoot"
const val FIELD_HOST = "editor-host"
const val FIELD_SSH_PORT = "editor-sshPort"
const val FIELD_USER = "editor-user"
const val FIELD_KEY = "editor-sshKey"
const val FIELD_IGNORE = "editor-ignorePatterns"
const val FIELD_ADVANCED = "editor-advancedPrefs"
const val FIELD_SERVER_CMD = "editor-serverCommand"
const val FIELD_AUTO_SYNC = "editor-autoSync"
const val FIELD_INTERVAL = "editor-autoSyncInterval"
const val BROWSE_LOCAL = "editor-browseLocal"
const val SAVE_BUTTON = "editor-save"
const val VALIDATION_ERROR = "editor-validationError"

private const val TAG = "ProfileEditorScreen"

val AUTO_SYNC_INTERVALS_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)

internal fun intervalLabel(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h"

@Composable
fun ProfileEditorScreen(
    profileId: String?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { ServiceLocator.profiles(context) }
    val vault = remember(context) { ServiceLocator.keys(context) }
    val scope = rememberCoroutineScope()
    var hasAccess by remember { mutableStateOf(hasAllFilesAccess(context)) }
    val requestAccess = rememberAllFilesAccessRequest { hasAccess = it }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {}
    val launchBatteryExemption: () -> Unit = {
        batteryExemptionIntent(context)?.let { intent ->
            // Some devices ship without a handler for the battery-optimization settings
            // screen; that must not crash the editor.
            try {
                batteryLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                // No handler available: skip the exemption prompt, but leave a trace.
                Log.w(TAG, "no activity to handle battery-optimization request", e)
            }
        }
    }
    // Continue to the battery-exemption prompt only after the notification request has
    // resolved: launching the settings activity while the runtime-permission dialog is
    // still in flight can drop that result or dismiss the dialog.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { launchBatteryExemption() }
    // Auto-sync runs in the background, so when the user turns it on we ask for the
    // notification permission (to promote work to a foreground service) and, if the app
    // is still battery-optimized, prompt for the exemption that lets it survive Doze.
    val onAutoSyncEnabled: () -> Unit = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchBatteryExemption()
        }
    }

    val loaded by produceState<EditorData?>(initialValue = null, repository, vault, profileId) {
        value = EditorData(
            profile = profileId?.let { repository.get(it) },
            keys = vault.keys(),
        )
    }

    val data = loaded
    if (data == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    ProfileEditorContent(
        initial = data.profile,
        keys = data.keys,
        onSave = { profile ->
            scope.launch {
                repository.save(profile)
                SyncScheduler(context).reconcile(
                    repository.profiles(),
                    ServiceLocator.settings(context).get(),
                )
                onSaved()
            }
        },
        hasLocalAccess = hasAccess,
        onRequestAccess = requestAccess,
        onAutoSyncEnabled = onAutoSyncEnabled,
        modifier = modifier,
    )
}

private data class EditorData(val profile: Profile?, val keys: List<SshKey>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditorContent(
    initial: Profile?,
    keys: List<SshKey>,
    onSave: (Profile) -> Unit,
    modifier: Modifier = Modifier,
    startDir: File = File("/storage/emulated/0"),
    hasLocalAccess: Boolean = true,
    onRequestAccess: () -> Unit = {},
    onAutoSyncEnabled: () -> Unit = {},
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var localRoot by remember(initial) { mutableStateOf(initial?.localRoot ?: "") }
    var remoteRoot by remember(initial) { mutableStateOf(initial?.remoteRoot ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var sshPort by remember(initial) { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var keyId by remember(initial) {
        mutableStateOf(initial?.sshKeyId?.takeIf { it.isNotEmpty() } ?: keys.firstOrNull()?.id.orEmpty())
    }
    var ignoreText by remember(initial) {
        mutableStateOf(initial?.ignorePatterns?.joinToString("\n") ?: "")
    }
    var serverCommand by remember(initial) { mutableStateOf(initial?.serverCommand ?: "unison") }
    var advanced by remember(initial) { mutableStateOf(initial?.advancedPrefs ?: "") }
    var autoSyncEnabled by remember(initial) { mutableStateOf(initial?.autoSyncEnabled ?: false) }
    var intervalMinutes by remember(initial) { mutableStateOf(initial?.autoSyncIntervalMinutes ?: 60) }
    var intervalMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf(false) }

    fun save() {
        val missing = buildList {
            if (name.isBlank()) add("name")
            if (localRoot.isBlank()) add("local root")
            if (remoteRoot.isBlank()) add("remote root")
            if (host.isBlank()) add("host")
            if (user.isBlank()) add("user")
            if (keyId.isBlank()) add("SSH key (create one on the Keys screen)")
        }
        if (missing.isNotEmpty()) {
            error = "Required: " + missing.joinToString(", ")
            return
        }
        error = null
        onSave(
            Profile(
                id = initial?.id.orEmpty(),
                name = name.trim(),
                localRoot = localRoot.trim(),
                remoteRoot = remoteRoot.trim(),
                host = host.trim(),
                sshPort = sshPort.toIntOrNull() ?: 22,
                user = user.trim(),
                remoteSocketPort = initial?.remoteSocketPort ?: 22333,
                sshKeyId = keyId,
                transport = initial?.transport ?: Transport.SSH_EXEC,
                serverCommand = serverCommand.trim().ifEmpty { "unison" },
                ignorePatterns = ignoreText.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                advancedPrefs = advanced,
                autoSyncEnabled = autoSyncEnabled,
                autoSyncIntervalMinutes = intervalMinutes,
                lastSyncedAt = initial?.lastSyncedAt,
                lastResult = initial?.lastResult,
            ),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = if (initial == null) "New profile" else "Edit profile") },
                actions = {
                    TextButton(
                        onClick = { save() },
                        modifier = Modifier.testTag(SAVE_BUTTON),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(VALIDATION_ERROR),
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_NAME),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = localRoot,
                    onValueChange = { localRoot = it },
                    label = { Text("Local root") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(FIELD_LOCAL_ROOT),
                )
                OutlinedButton(
                    onClick = { browsing = true },
                    modifier = Modifier.testTag(BROWSE_LOCAL),
                ) { Text("Browse") }
            }
            OutlinedTextField(
                value = remoteRoot,
                onValueChange = { remoteRoot = it },
                label = { Text("Remote root") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_REMOTE_ROOT),
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_HOST),
            )
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it },
                label = { Text("SSH port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_SSH_PORT),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_USER),
            )

            Text(text = "Connection", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = serverCommand,
                onValueChange = { serverCommand = it },
                label = { Text("Remote unison command") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_SERVER_CMD),
            )

            Box {
                OutlinedButton(
                    onClick = { keyMenu = true },
                    modifier = Modifier.fillMaxWidth().testTag(FIELD_KEY),
                ) {
                    val selected = keys.firstOrNull { it.id == keyId }?.name ?: "No key"
                    Text(text = "SSH key: $selected")
                }
                DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                    keys.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key.name) },
                            onClick = {
                                keyId = key.id
                                keyMenu = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = ignoreText,
                onValueChange = { ignoreText = it },
                label = { Text("Ignore patterns (one per line)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_IGNORE),
            )

            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = advanced,
                onValueChange = { advanced = it },
                label = { Text("Advanced preferences") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_ADVANCED),
            )

            Text(
                text = "Scheduled sync",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(text = "Sync automatically")
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { enabled ->
                        autoSyncEnabled = enabled
                        if (enabled) onAutoSyncEnabled()
                    },
                    modifier = Modifier.testTag(FIELD_AUTO_SYNC),
                )
            }
            if (autoSyncEnabled) {
                Text(text = "Sync interval", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(
                        onClick = { intervalMenu = true },
                        modifier = Modifier.fillMaxWidth().testTag(FIELD_INTERVAL),
                    ) { Text(text = intervalLabel(intervalMinutes)) }
                    DropdownMenu(expanded = intervalMenu, onDismissRequest = { intervalMenu = false }) {
                        AUTO_SYNC_INTERVALS_MINUTES.forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text(intervalLabel(minutes)) },
                                onClick = {
                                    intervalMinutes = minutes
                                    intervalMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (browsing) {
        if (hasLocalAccess) {
            AlertDialog(
                onDismissRequest = { browsing = false },
                confirmButton = {},
                title = { Text("Choose local folder") },
                text = {
                    PathBrowser(
                        startDir = startDir,
                        onPicked = { picked ->
                            localRoot = picked
                            browsing = false
                        },
                    )
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { browsing = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            browsing = false
                            onRequestAccess()
                        },
                        modifier = Modifier.testTag(GRANT_ACCESS_TAG),
                    ) { Text("Grant All Files Access") }
                },
                dismissButton = {
                    TextButton(onClick = { browsing = false }) { Text("Cancel") }
                },
                title = { Text("Storage access needed") },
                text = {
                    Text(
                        "UnisonDroid needs All Files Access to browse and sync folders under " +
                            "/storage/emulated/0. Grant it, then reopen the browser.",
                    )
                },
            )
        }
    }
}
