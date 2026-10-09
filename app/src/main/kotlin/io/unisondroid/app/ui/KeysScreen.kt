package io.unisondroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.unisondroid.app.data.KeyVaultException
import io.unisondroid.app.data.SshKey
import io.unisondroid.app.service.ServiceLocator
import kotlinx.coroutines.launch

const val KEYS_GENERATE_TAG = "keys-generate"
const val KEYS_GENERATE_NAME_FIELD = "keys-generate-name"
const val KEYS_GENERATE_CONFIRM_TAG = "keys-generate-confirm"
const val KEYS_IMPORT_TAG = "keys-import"
const val KEYS_IMPORT_NAME_FIELD = "keys-import-name"
const val KEYS_IMPORT_PEM_FIELD = "keys-import-pem"
const val KEYS_IMPORT_CONFIRM_TAG = "keys-import-confirm"
const val KEYS_COPY_PUBLIC_TAG = "keys-copy-public"
const val KEYS_SHARE_TAG = "keys-share"
const val KEYS_EMPTY_TAG = "keys-empty"
const val IMPORT_ERROR_MESSAGE = "Import failed: not a valid OpenSSH private key"

@Composable
fun KeysScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val vault = remember(context) { ServiceLocator.keys(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var keys by remember { mutableStateOf<List<SshKey>>(emptyList()) }

    suspend fun reload() {
        keys = vault.keys()
    }

    LaunchedEffect(vault) { reload() }

    KeysContent(
        keys = keys,
        snackbarHostState = snackbarHostState,
        onGenerate = { name ->
            scope.launch {
                vault.generate(name)
                reload()
            }
        },
        onImport = { name, pem ->
            scope.launch {
                try {
                    vault.importOpenSsh(name, pem)
                    reload()
                } catch (e: KeyVaultException) {
                    snackbarHostState.showSnackbar(IMPORT_ERROR_MESSAGE)
                }
            }
        },
        onCopy = { key ->
            clipboard.setPrimaryClip(ClipData.newPlainText(key.name, key.publicKey))
        },
        onShare = { key -> context.startActivity(sharePublicKey(key)) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeysContent(
    keys: List<SshKey>,
    snackbarHostState: SnackbarHostState,
    onGenerate: (String) -> Unit,
    onImport: (String, String) -> Unit,
    onCopy: (SshKey) -> Unit,
    onShare: (SshKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var generateName by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("imported") }
    var importPem by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "SSH keys") },
                actions = {
                    TextButton(
                        onClick = { showImport = true },
                        modifier = Modifier.testTag(KEYS_IMPORT_TAG),
                    ) { Text("Import") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { generateName = ""; showGenerate = true },
                modifier = Modifier.testTag(KEYS_GENERATE_TAG),
            ) { Text("Generate") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (keys.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
            ) {
                Text(
                    text = "No keys yet. Generate a new key or import an existing OpenSSH key.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(KEYS_EMPTY_TAG),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(keys, key = { it.id }) { key ->
                    KeyRow(key = key, onCopy = { onCopy(key) }, onShare = { onShare(key) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showGenerate) {
        AlertDialog(
            onDismissRequest = { showGenerate = false },
            title = { Text("Generate key") },
            text = {
                OutlinedTextField(
                    value = generateName,
                    onValueChange = { generateName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.testTag(KEYS_GENERATE_NAME_FIELD),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onGenerate(generateName.trim().ifEmpty { "key" })
                        showGenerate = false
                    },
                    modifier = Modifier.testTag(KEYS_GENERATE_CONFIRM_TAG),
                ) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { showGenerate = false }) { Text("Cancel") }
            },
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.testTag(KEYS_IMPORT_NAME_FIELD),
                    )
                    OutlinedTextField(
                        value = importPem,
                        onValueChange = { importPem = it },
                        label = { Text("OpenSSH private key (PEM)") },
                        minLines = 4,
                        modifier = Modifier.testTag(KEYS_IMPORT_PEM_FIELD),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImport(importName.trim().ifEmpty { "imported" }, importPem)
                        showImport = false
                    },
                    modifier = Modifier.testTag(KEYS_IMPORT_CONFIRM_TAG),
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun KeyRow(key: SshKey, onCopy: () -> Unit, onShare: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = key.name, style = MaterialTheme.typography.titleMedium)
            Text(text = key.publicKey, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.testTag(KEYS_COPY_PUBLIC_TAG),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.testTag(KEYS_SHARE_TAG),
                ) { Text("Share") }
            }
        }
    }
}

private fun sharePublicKey(key: SshKey): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "UnisonDroid public key: ${key.name}")
        putExtra(Intent.EXTRA_TEXT, key.publicKey)
    }
    return Intent.createChooser(send, "Share public key")
}
