package io.unisondroid.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File

const val PATH_CWD = "pathBrowserCwd"
const val PATH_ENTRY_PREFIX = "pathBrowserEntry-"
const val PATH_PICK = "pathBrowserPick"
const val PATH_NEW_DIR = "pathBrowserNewDir"
const val PATH_CREATE_DIR = "pathBrowserCreateDir"

@Composable
fun PathBrowser(
    startDir: File,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember(startDir) { mutableStateOf(startDir) }
    var refresh by remember { mutableIntStateOf(0) }
    var newDirName by remember { mutableStateOf("") }

    val entries = remember(current, refresh) {
        current.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.toList()
            .orEmpty()
    }
    val parent = current.parentFile

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = current.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(PATH_CWD),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { parent?.let { current = it } },
                enabled = parent != null,
            ) { Text("..") }
            TextButton(
                onClick = { onPicked(current.absolutePath) },
                modifier = Modifier.testTag(PATH_PICK),
            ) { Text("Use this folder") }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 320.dp),
        ) {
            items(entries, key = { it.absolutePath }) { file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                current = file
                                refresh++
                            } else {
                                onPicked(file.absolutePath)
                            }
                        }
                        .testTag("$PATH_ENTRY_PREFIX${file.name}")
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    Text(text = file.name, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newDirName,
                onValueChange = { newDirName = it },
                label = { Text("New folder") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(PATH_NEW_DIR),
            )
            Button(
                onClick = {
                    val name = newDirName.trim()
                    if (name.isNotEmpty()) {
                        File(current, name).mkdirs()
                        newDirName = ""
                        refresh++
                    }
                },
                modifier = Modifier.testTag(PATH_CREATE_DIR),
            ) { Text("Create") }
        }
    }
}
