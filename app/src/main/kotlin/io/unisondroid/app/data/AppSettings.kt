package io.unisondroid.app.data

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(val syncOnMobileData: Boolean = true)

class SettingsRepository(private val store: JsonStore) {

    suspend fun get(): AppSettings = store.read<AppSettings>(SETTINGS) ?: AppSettings()

    suspend fun set(settings: AppSettings) {
        store.write(SETTINGS, settings)
    }

    private companion object {
        const val SETTINGS = "settings"
    }
}
