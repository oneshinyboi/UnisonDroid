package io.unisondroid.app.ui

import io.unisondroid.app.sync.SyncVariant

/** Human-readable title for a run variant, used by the run screen and the long-press menu. */
internal fun SyncVariant.title(): String = when (this) {
    SyncVariant.TWO_WAY -> "Sync"
    SyncVariant.COPY_TO_SERVER -> "Copy to server"
    SyncVariant.MIRROR_TO_SERVER -> "Mirror to server"
    SyncVariant.COPY_FROM_SERVER -> "Copy from server"
    SyncVariant.MIRROR_FROM_SERVER -> "Mirror from server"
    SyncVariant.TEST_CONNECTION -> "Test connection"
}
