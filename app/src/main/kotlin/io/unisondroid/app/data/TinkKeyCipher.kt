package io.unisondroid.app.data

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts private keys at rest with Tink. The AEAD keyset is stored in
 * shared preferences, itself encrypted by a master key held in the Android
 * Keystore.
 *
 * The keyset is resolved lazily: constructing a [KeyVault] must not touch the
 * Android Keystore, and the master key is only needed once a key is actually
 * encrypted or decrypted.
 */
class TinkKeyCipher(private val context: Context) : KeyCipher {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_PREF, KEYSETS_PREF)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain, null)

    override fun decrypt(blob: ByteArray): ByteArray = aead.decrypt(blob, null)

    private companion object {
        const val KEYSET_PREF = "unisondroid-keyset"
        const val KEYSETS_PREF = "unisondroid-keysets"
        const val MASTER_KEY_URI = "android-keystore://unisondroid-master"
    }
}
