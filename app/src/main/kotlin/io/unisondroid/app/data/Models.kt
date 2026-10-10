package io.unisondroid.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val localRoot: String,
    val remoteRoot: String,
    val host: String,
    val sshPort: Int = 22,
    val user: String,
    val remoteSocketPort: Int = 22333,
    val sshKeyId: String,
    val transport: Transport = Transport.SSH_EXEC,
    val serverCommand: String = "unison",
    val ignorePatterns: List<String> = emptyList(),
    val advancedPrefs: String = "",
    val autoSyncEnabled: Boolean = false,
    val autoSyncIntervalMinutes: Int = 60,
    val lastSyncedAt: Long? = null,
    val lastResult: SyncResult? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    val lastConflicts: List<ConflictRecord> = emptyList(),
)

enum class Transport { SSH_EXEC, SOCKET }

enum class SyncResult { NEVER, OK, WARNINGS, FAILED }

enum class ConflictPolicy { SKIP, PREFER_NEWER, PREFER_OLDER, PREFER_LOCAL, PREFER_REMOTE, KEEP_BOTH }

@Serializable
data class SideInfo(
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val kind: String? = null,
)

@Serializable
data class ConflictRecord(
    val path: String,
    val reason: String = "",
    val local: SideInfo? = null,
    val remote: SideInfo? = null,
    val resolvable: Boolean = true,
)

@Serializable
data class FailedRecord(val path: String, val message: String = "")

@Serializable
data class SshKey(
    val id: String,
    val name: String,
    val publicKey: String,
    val encryptedPrivateBase64: String,
)
