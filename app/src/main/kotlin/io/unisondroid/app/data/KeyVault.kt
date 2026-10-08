package io.unisondroid.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.KeyStore
import java.security.SecureRandom
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

class KeyVault(private val store: JsonStore, private val cipher: KeyCipher) {

    private val mutex = Mutex()

    suspend fun generate(name: String): SshKey = mutex.withLock {
        val (pem, publicBlob) = generateEd25519OpenSshKeyPem(name)
        persist(name, pem, publicLine("ssh-ed25519", publicBlob, name))
    }

    suspend fun importOpenSsh(name: String, pem: String): SshKey = mutex.withLock {
        val (type, publicBlob) = try {
            val provider = SSHClient().loadKeys(pem, null, null)
            provider.type.toString() to Buffer.PlainBuffer().putPublicKey(provider.public).compactData
        } catch (e: Exception) {
            throw KeyVaultException("Malformed OpenSSH private key", e)
        }
        persist(name, pem, publicLine(type, publicBlob, name))
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

internal fun publicLine(type: String, publicBlob: ByteArray, comment: String): String =
    "$type ${Base64.getEncoder().encodeToString(publicBlob)} $comment"

internal fun generateEd25519OpenSshKeyPem(comment: String): Pair<String, ByteArray> {
    val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    val publicKey = privateKey.generatePublicKey()
    val pubBytes = publicKey.encoded
    val seed = privateKey.encoded

    val publicBlob = Buffer.PlainBuffer()
        .putString("ssh-ed25519")
        .putString(pubBytes)
        .compactData

    val privateKeyBody = Buffer.PlainBuffer()
        .putUInt32(CHECK_INT)
        .putUInt32(CHECK_INT)
        .putString("ssh-ed25519")
        .putString(pubBytes)
        .putString(seed + pubBytes)
        .putString(comment)
        .compactData
    val padding = ByteArray((8 - privateKeyBody.size % 8) % 8) { (it + 1).toByte() }

    val container = Buffer.PlainBuffer()
        .putRawBytes("openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1))
        .putString("none")
        .putString("none")
        .putString("")
        .putUInt32(1)
        .putString(publicBlob)
        .putString(privateKeyBody + padding)
        .compactData
    val base64 = Base64.getMimeEncoder(70, byteArrayOf('\n'.code.toByte())).encodeToString(container)
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$base64\n-----END OPENSSH PRIVATE KEY-----\n"
    return pem to publicBlob
}

private const val CHECK_INT = 1_234_567_890L
