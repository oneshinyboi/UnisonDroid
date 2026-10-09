package io.unisondroid.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.unisondroid.app.sync.SshTool
import io.unisondroid.app.sync.SshToolException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class KeystoreAesCipher : KeyCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES)
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH_BYTES) { "Encrypted blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LENGTH_BYTES))
        return cipher.doFinal(blob, IV_LENGTH_BYTES, blob.size - IV_LENGTH_BYTES)
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "unisondroid-master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_LENGTH_BYTES = 12
    }
}

class KeyVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

class KeyVault(
    private val store: JsonStore,
    private val cipher: KeyCipher,
    private val tool: SshTool,
) {

    private val mutex = Mutex()

    suspend fun generate(name: String): SshKey = mutex.withLock {
        val generated = try {
            tool.generateKey(name)
        } catch (e: SshToolException) {
            throw KeyVaultException("Key generation failed", e)
        }
        persist(name, generated.privatePem, generated.publicKeyLine)
    }

    suspend fun importOpenSsh(name: String, pem: String): SshKey = mutex.withLock {
        val publicKey = try {
            tool.derivePublicKey(pem)
        } catch (e: SshToolException) {
            throw KeyVaultException("Malformed OpenSSH private key", e)
        }
        val parts = publicKey.trim().split(" ")
        val type = parts.getOrElse(0) { "unknown" }
        val blob = parts.getOrNull(1).orEmpty()
        persist(name, pem, publicLine(type, blob, name))
    }

    suspend fun keys(): List<SshKey> = readAll()

    suspend fun privateKeyPem(id: String): String {
        val key = readAll().firstOrNull { it.id == id } ?: throw KeyVaultException("Unknown key: $id")
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(key.encryptedPrivateBase64)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw KeyVaultException("Failed to decrypt key: $id", e)
        }
    }

    private suspend fun persist(name: String, pem: String, publicKey: String): SshKey {
        val key = SshKey(
            id = UUID.randomUUID().toString(),
            name = name,
            publicKey = publicKey,
            encryptedPrivateBase64 = Base64.getEncoder()
                .encodeToString(cipher.encrypt(pem.toByteArray(Charsets.UTF_8))),
        )
        store.write(FILE_NAME, readAll() + key)
        return key
    }

    private suspend fun readAll(): List<SshKey> = store.read<List<SshKey>>(FILE_NAME) ?: emptyList()

    private companion object {
        const val FILE_NAME = "ssh_keys"
    }
}

internal fun publicLine(type: String, blobBase64: String, comment: String): String =
    "$type $blobBase64 $comment"
