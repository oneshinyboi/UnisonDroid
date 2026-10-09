package io.unisondroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

const val GRANT_ACCESS_TAG = "grantAllFilesAccess"

fun hasAllFilesAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> try {
        Environment.isExternalStorageManager()
    } catch (t: RuntimeException) {
        false
    }

    else -> ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
}

fun allFilesAccessIntent(context: Context): Intent? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

    else -> null
}

@Composable
fun rememberAllFilesAccessRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onResult(hasAllFilesAccess(context)) }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return {
        val intent = allFilesAccessIntent(context)
        if (intent != null) {
            settingsLauncher.launch(intent)
        } else {
            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

@Composable
fun AllFilesAccessCard(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Storage access needed", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "UnisonDroid needs All Files Access to read and write the folders you " +
                    "sync under /storage/emulated/0.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onGrant,
                modifier = Modifier.testTag(GRANT_ACCESS_TAG),
            ) { Text(text = "Grant All Files Access") }
        }
    }
}
