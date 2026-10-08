package io.unisondroid.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TofuVerdict { APPROVED, UNKNOWN, CHANGED }

class HostKeyStore(private val store: JsonStore) {

    private val mutex = Mutex()

    suspend fun known(host: String, port: Int): KnownHost? =
        readAll().firstOrNull { it.host == host && it.port == port }

    suspend fun approve(host: String, port: Int, fingerprint: String) {
        mutex.withLock {
            val others = readAll().filterNot { it.host == host && it.port == port }
            store.write(FILE_NAME, others + KnownHost(host, port, fingerprint, approvedAt = System.currentTimeMillis()))
        }
    }

    suspend fun verify(host: String, port: Int, fingerprint: String): TofuVerdict {
        val known = known(host, port)
        return when {
            known == null -> TofuVerdict.UNKNOWN
            known.fingerprint == fingerprint -> TofuVerdict.APPROVED
            else -> TofuVerdict.CHANGED
        }
    }

    private suspend fun readAll(): List<KnownHost> = store.read<List<KnownHost>>(FILE_NAME) ?: emptyList()

    private companion object {
        const val FILE_NAME = "known_hosts"
    }
}
