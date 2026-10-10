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

/** Menu label for a run variant: clearer about the keep-vs-delete consequence than [title]. */
internal fun SyncVariant.menuLabel(): String = when (this) {
    SyncVariant.TWO_WAY -> "Sync now"
    SyncVariant.COPY_TO_SERVER -> "Copy to server (keep extras)"
    SyncVariant.MIRROR_TO_SERVER -> "Mirror to server (exact copy)"
    SyncVariant.COPY_FROM_SERVER -> "Copy from server (keep extras)"
    SyncVariant.MIRROR_FROM_SERVER -> "Mirror from server (exact copy)"
    SyncVariant.TEST_CONNECTION -> "Test connection"
}
