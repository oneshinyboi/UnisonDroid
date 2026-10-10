package io.unisondroid.app.service

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/**
 * Detects whether a local root sits on a removable storage volume (an SD card
 * or USB OTG drive).
 *
 * Portable removable volumes are normally FAT/exFAT and therefore
 * case-insensitive, unlike Android's internal storage (ext4/f2fs). The sync
 * profile uses this to decide the replica's case mode.
 *
 * Any failure to resolve a volume is reported as "not removable" so the safer
 * case-sensitive mode is used by default; a case-sensitive replica over a
 * genuinely case-insensitive one is still overridable via advanced prefs.
 */
object RemovableVolumes {

    fun isRemovable(context: Context, path: String): Boolean {
        val manager = context.getSystemService(StorageManager::class.java) ?: return false
        return runCatching {
            manager.getStorageVolume(File(path))?.isRemovable == true
        }.getOrDefault(false)
    }
}
