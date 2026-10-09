package io.unisondroid.app.data

import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import java.util.Base64

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
