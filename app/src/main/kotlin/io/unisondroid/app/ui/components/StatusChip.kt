package io.unisondroid.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.unisondroid.app.data.SyncResult

const val STATUS_CHIP_TAG = "statusChip"

internal fun statusLabel(result: SyncResult?): String = when (result ?: SyncResult.NEVER) {
    SyncResult.NEVER -> "Never"
    SyncResult.OK -> "OK"
    SyncResult.WARNINGS -> "Warnings"
    SyncResult.FAILED -> "Failed"
}

@Composable
fun StatusChip(result: SyncResult?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (result ?: SyncResult.NEVER) {
        SyncResult.NEVER -> scheme.surfaceVariant to scheme.onSurfaceVariant
        SyncResult.OK -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SyncResult.WARNINGS -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SyncResult.FAILED -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        modifier = modifier.testTag(STATUS_CHIP_TAG),
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = statusLabel(result),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
