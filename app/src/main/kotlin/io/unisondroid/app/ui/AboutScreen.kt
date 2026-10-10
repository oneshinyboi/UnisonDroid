package io.unisondroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.unisondroid.app.sync.UnisonInfo

const val ABOUT_VERSION_TAG = "about-version"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = "About") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "UnisonDroid", style = MaterialTheme.typography.headlineSmall)
            Text(text = "Bundled Unison versions", style = MaterialTheme.typography.titleMedium)
            UnisonInfo.BUNDLED.forEachIndexed { index, bundled ->
                Text(
                    text = "${bundled.version}  (${bundled.fileName})",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = if (index == 0) Modifier.testTag(ABOUT_VERSION_TAG) else Modifier,
                )
            }
            Text(
                text = "UnisonDroid is a file-sync client for Android. It ships the unison " +
                    "engine and connects over SSH to a unison socket running on your computer.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(text = "Server setup", style = MaterialTheme.typography.titleLarge)
            SetupStep(
                "1. Install unison and an SSH server",
                "Install unison and OpenSSH on the computer you want to sync with. " +
                    "Verify with `unison -version`.",
            )
            SetupStep(
                "2. Add your public key",
                "On the Keys screen, generate a key (or import one), copy the public line, " +
                    "and append it to ~/.ssh/authorized_keys on the server.",
            )
            SetupStep(
                "3. Start the unison socket",
                "Run `unison -socket 22333` on the server. Keep it running with the " +
                    "unison-socket.service systemd user unit shown in docs/SERVER-SETUP.md.",
            )
            SetupStep(
                "4. Create a profile",
                "Add a profile with the server host, user, SSH port, remote root and the " +
                    "socket port 22333, then sync.",
            )

            Text(text = "License", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "UnisonDroid is free software: you can redistribute it and/or modify " +
                    "it under the terms of the GNU General Public License as published by the " +
                    "Free Software Foundation, either version 3 of the License, or (at your " +
                    "option) any later version.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "UnisonDroid is distributed in the hope that it will be useful, but " +
                    "WITHOUT ANY WARRANTY; without even the implied warranty of " +
                    "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General " +
                    "Public License for more details.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "You should have received a copy of the GNU General Public License " +
                    "along with UnisonDroid. If not, see https://www.gnu.org/licenses/.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SetupStep(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}
