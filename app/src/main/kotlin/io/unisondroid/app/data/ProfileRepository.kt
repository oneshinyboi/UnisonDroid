package io.unisondroid.app.data

import java.util.UUID

class ProfileRepository(private val store: JsonStore) {

    suspend fun profiles(): List<Profile> = store.read<List<Profile>>(PROFILES) ?: emptyList()

    suspend fun save(p: Profile) {
        val id = p.id.ifEmpty { newId() }
        val saved = p.copy(id = id)
        store.write(PROFILES, profiles().filterNot { it.id == id } + saved)
    }

    suspend fun delete(id: String) {
        store.write(PROFILES, profiles().filterNot { it.id == id })
    }

    suspend fun get(id: String): Profile? = profiles().firstOrNull { it.id == id }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    private companion object {
        const val PROFILES = "profiles"
    }
}
