package io.unisondroid.app.sync

import io.unisondroid.app.data.Transport
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ServerSocketChannel
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlin.concurrent.thread

data class TunnelSpec(
    val host: String,
    val port: Int,
    val user: String,
    val privateKeyPem: String,
    val remoteSocketPort: Int = 22333,
    val transport: Transport = Transport.SSH_EXEC,
    val serverCommand: String = "unison",
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

/**
 * Opens an authenticated SSH session and exposes the remote Unison endpoint as a
 * loopback TCP port that the bundled Unison connects to via a `socket://` root.
 *
 * Two transports:
 *  - [Transport.SOCKET]: forward to a long-running `unison -socket` daemon.
 *  - [Transport.SSH_EXEC]: exec `unison -server` over the SSH session and relay
 *    the byte stream (the same RPC protocol the socket daemon speaks), so no
 *    server daemon is needed.
 */
class SshjTunnel : SshTunnel {

    override suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle = withContext(Dispatchers.IO) {
        SshCrypto.ensureInstalled()
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

            val serverChannel = ServerSocketChannel.open()
            serverChannel.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), BACKLOG)
            val serverSocket = serverChannel.socket()
            val closed = CompletableDeferred<Unit>()
            val worker = when (spec.transport) {
                Transport.SOCKET -> startForwarding(client, serverSocket, spec.remoteSocketPort, closed)
                Transport.SSH_EXEC -> startExecRelay(client, serverChannel, spec.serverCommand, closed)
            }
            object : TunnelHandle {
                override val localPort: Int = serverSocket.localPort

                override fun close() {
                    client.runCatching { disconnect() }
                    // Closing the channel (rather than only the socket) reliably
                    // unblocks a concurrent accept() on the worker thread.
                    serverChannel.runCatching { close() }
                    serverSocket.runCatching { close() }
                    // Wait for the worker to unwind so the local port is fully
                    // released before callers proceed.
                    runCatching { worker.join(CLOSE_JOIN_MS) }
                    closed.complete(Unit)
                }

                override suspend fun awaitClosed() = closed.await()
            }
        } catch (t: Throwable) {
            client.runCatching { disconnect() }
            throw t
        }
    }

    private fun startForwarding(
        client: SSHClient,
        serverSocket: ServerSocket,
        remotePort: Int,
        closed: CompletableDeferred<Unit>,
    ): Thread {
        val forwarder = client.newLocalPortForwarder(
            Parameters(LOOPBACK, serverSocket.localPort, LOOPBACK, remotePort),
            serverSocket,
        )
        return thread(name = "ssh-tunnel-${serverSocket.localPort}", isDaemon = true) {
            try {
                forwarder.listen()
            } catch (_: IOException) {
            } finally {
                serverSocket.runCatching { close() }
                closed.complete(Unit)
            }
        }
    }

    private fun startExecRelay(
        client: SSHClient,
        serverChannel: ServerSocketChannel,
        serverCommand: String,
        closed: CompletableDeferred<Unit>,
    ): Thread {
        val session = client.startSession()
        val command = session.exec("$serverCommand -server")
        return thread(name = "ssh-exec-relay-${serverChannel.socket().localPort}", isDaemon = true) {
            try {
                serverChannel.accept().socket().use { local ->
                    // Drain the remote stderr so the channel's window can't fill
                    // and block the remote `unison -server`.
                    val err = thread(name = "ssh-exec-relay-err", isDaemon = true) {
                        runCatching { command.errorStream?.copyTo(System.err) }
                    }
                    val up = thread(name = "ssh-exec-relay-up", isDaemon = true) {
                        // Flush after every write: sshj buffers the channel
                        // output, and unison waits for the server's reply before
                        // sending more, so a deferred flush would deadlock.
                        runCatching { pump(local.getInputStream(), command.outputStream) }
                        runCatching { command.outputStream.close() }
                    }
                    val down = thread(name = "ssh-exec-relay-down", isDaemon = true) {
                        runCatching { pump(command.inputStream, local.getOutputStream()) }
                        runCatching { local.close() }
                    }
                    up.join(RELAY_JOIN_MS)
                    down.join(RELAY_JOIN_MS)
                    err.join(RELAY_JOIN_MS)
                }
            } catch (_: IOException) {
            } finally {
                runCatching { command.close() }
                runCatching { session.close() }
                serverChannel.runCatching { close() }
                closed.complete(Unit)
            }
        }
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            output.flush()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 50
        const val RELAY_JOIN_MS = 5_000L
        const val CLOSE_JOIN_MS = 3_000L
        const val RELAY_BUFFER = 32 * 1024
    }
}
