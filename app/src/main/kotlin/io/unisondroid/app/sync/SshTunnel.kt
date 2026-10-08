package io.unisondroid.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlin.concurrent.thread

data class TunnelSpec(
    val host: String,
    val port: Int,
    val user: String,
    val privateKeyPem: String,
    val remoteSocketPort: Int,
)

fun interface HostKeyDecision {
    suspend fun decide(fingerprint: String): Boolean
}

interface SshTunnel {
    suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle
}

interface TunnelHandle {
    val localPort: Int
    fun close()
    suspend fun awaitClosed()
}

internal fun hostKeyFingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

class SshjTunnel : SshTunnel {

    override suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle = withContext(Dispatchers.IO) {
        val client = SSHClient()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                val fingerprint = key?.let(::hostKeyFingerprint) ?: return false
                return runBlocking { decision.decide(fingerprint) }
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        try {
            client.connect(spec.host, spec.port)
            client.authPublickey(spec.user, client.loadKeys(spec.privateKeyPem, null, null))

            val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
            val forwarder = client.newLocalPortForwarder(
                Parameters(LOOPBACK, serverSocket.localPort, LOOPBACK, spec.remoteSocketPort),
                serverSocket,
            )
            val closed = CompletableDeferred<Unit>()
            thread(name = "ssh-tunnel-${serverSocket.localPort}", isDaemon = true) {
                try {
                    forwarder.listen()
                } catch (_: IOException) {
                } finally {
                    serverSocket.runCatching { close() }
                    closed.complete(Unit)
                }
            }
            object : TunnelHandle {
                override val localPort: Int = serverSocket.localPort

                override fun close() {
                    client.runCatching { disconnect() }
                    serverSocket.runCatching { close() }
                    forwarder.runCatching { close() }
                    closed.complete(Unit)
                }

                override suspend fun awaitClosed() = closed.await()
            }
        } catch (t: Throwable) {
            client.runCatching { disconnect() }
            throw t
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 50
    }
}
