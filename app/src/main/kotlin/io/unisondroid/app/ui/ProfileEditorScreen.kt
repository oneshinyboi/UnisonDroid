package io.unisondroid.app.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.SshKey
import io.unisondroid.app.service.ServiceLocator
import kotlinx.coroutines.launch
import java.io.File

const val FIELD_NAME = "editor-name"
const val FIELD_LOCAL_ROOT = "editor-localRoot"
const val FIELD_REMOTE_ROOT = "editor-remoteRoot"
const val FIELD_HOST = "editor-host"
const val FIELD_SSH_PORT = "editor-sshPort"
const val FIELD_USER = "editor-user"
const val FIELD_SOCKET_PORT = "editor-remoteSocketPort"
const val FIELD_KEY = "editor-sshKey"
const val FIELD_IGNORE = "editor-ignorePatterns"
const val FIELD_ADVANCED = "editor-advancedPrefs"
const val BROWSE_LOCAL = "editor-browseLocal"
const val SAVE_BUTTON = "editor-save"
const val VALIDATION_ERROR = "editor-validationError"

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
                onSaved()
            }
        },
        hasLocalAccess = hasAccess,
        onRequestAccess = requestAccess,
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
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var localRoot by remember(initial) { mutableStateOf(initial?.localRoot ?: "") }
    var remoteRoot by remember(initial) { mutableStateOf(initial?.remoteRoot ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var sshPort by remember(initial) { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var socketPort by remember(initial) {
        mutableStateOf((initial?.remoteSocketPort ?: 22333).toString())
    }
    var keyId by remember(initial) {
        mutableStateOf(initial?.sshKeyId?.takeIf { it.isNotEmpty() } ?: keys.firstOrNull()?.id.orEmpty())
    }
    var ignoreText by remember(initial) {
        mutableStateOf(initial?.ignorePatterns?.joinToString("\n") ?: "")
    }
    var advanced by remember(initial) { mutableStateOf(initial?.advancedPrefs ?: "") }
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
                remoteSocketPort = socketPort.toIntOrNull() ?: 22333,
                sshKeyId = keyId,
                ignorePatterns = ignoreText.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                advancedPrefs = advanced,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = sshPort,
                    onValueChange = { sshPort = it },
                    label = { Text("SSH port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(FIELD_SSH_PORT),
                )
                OutlinedTextField(
                    value = socketPort,
                    onValueChange = { socketPort = it },
                    label = { Text("Socket port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(FIELD_SOCKET_PORT),
                )
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FIELD_USER),
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
