package io.unisondroid.app.sync

import java.io.File

class FakeSshTool : SshTool {

    var generated: GeneratedKey = GeneratedKey(
        privatePem = "-----BEGIN OPENSSH PRIVATE KEY-----\nZmFrZQ==\n-----END OPENSSH PRIVATE KEY-----\n",
        publicKeyLine = "ssh-ed25519 AAAAZmFrZSBibG9i default-comment",
    )
    var derivedPublicKey: String = "ssh-ed25519 BBBBZGVyaXZlZA=="
    var deriveException: SshToolException? = null
    var scannedHostKeys: List<HostKeyEntry> = emptyList()
    var scanException: SshToolException? = null
    var hostIsKnown: Boolean = false
    var isHostKnownException: SshToolException? = null

    val generateCalls = mutableListOf<String>()
    val deriveCalls = mutableListOf<String>()
    val scanCalls = mutableListOf<Pair<String, Int>>()
    val knownCalls = mutableListOf<Triple<String, Int, File>>()

    override fun generateKey(comment: String): GeneratedKey {
        generateCalls += comment
        return generated
    }

    override fun derivePublicKey(pem: String): String {
        deriveCalls += pem
        deriveException?.let { throw it }
        return derivedPublicKey
    }

    override fun scanHostKeys(host: String, port: Int): List<HostKeyEntry> {
        scanCalls += host to port
        scanException?.let { throw it }
        return scannedHostKeys
    }

    override fun isHostKnown(host: String, port: Int, knownHosts: File): Boolean {
        knownCalls += Triple(host, port, knownHosts)
        isHostKnownException?.let { throw it }
        return hostIsKnown
    }
}
