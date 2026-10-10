package io.unisondroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import kotlinx.coroutines.launch

const val SETTINGS_TOGGLE_TAG = "settings-syncOnMobileData"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepository = remember(context) { ServiceLocator.settings(context) }
    val scope = rememberCoroutineScope()
    val loaded by produceState<AppSettings?>(initialValue = null, settingsRepository) {
        value = settingsRepository.get()
    }
    var syncOnMobileData by remember(loaded) {
        mutableStateOf(loaded?.syncOnMobileData ?: true)
    }

    SettingsContent(
        syncOnMobileData = syncOnMobileData,
        onToggle = { enabled ->
            syncOnMobileData = enabled
            scope.launch {
                val updated = AppSettings(syncOnMobileData = enabled)
                settingsRepository.set(updated)
                SyncScheduler(context).reconcile(
                    ServiceLocator.profiles(context).profiles(),
                    updated,
                )
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    syncOnMobileData: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = "Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Don't sync on mobile data")
                Switch(
                    checked = syncOnMobileData,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag(SETTINGS_TOGGLE_TAG),
                )
            }
            Text(
                text = "When enabled, automatic syncs wait for an unmetered connection.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
