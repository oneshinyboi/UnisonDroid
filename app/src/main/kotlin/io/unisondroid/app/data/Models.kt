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
    val lastSyncedAt: Long? = null,
    val lastResult: SyncResult? = null,
)

enum class Transport { SSH_EXEC, SOCKET }

enum class SyncResult { NEVER, OK, WARNINGS, FAILED }

@Serializable
data class SshKey(
    val id: String,
    val name: String,
    val publicKey: String,
    val encryptedPrivateBase64: String,
)

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val approvedAt: Long,
)
