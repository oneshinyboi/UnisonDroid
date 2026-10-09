package io.unisondroid.app.sync

import io.unisondroid.app.data.Transport
import io.unisondroid.app.data.generateEd25519OpenSshKeyPem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.TransportException
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

@Timeout(60)
class SshTunnelTest {

    private val authAttempts = AtomicInteger()
    private val events = mutableListOf<String>()
    private lateinit var sshd: SshServer
    private lateinit var echoSocket: ServerSocket
    private lateinit var echoThread: Thread
    private lateinit var serverKeyPair: KeyPair
    private lateinit var userKeyPem: String
    private lateinit var userPublicBlob: ByteArray
    private lateinit var expectedFingerprint: String

    @BeforeEach
    fun setUp() {
        val (pem, publicBlob) = generateEd25519OpenSshKeyPem("unisondroid-test")
        userKeyPem = pem
        userPublicBlob = publicBlob
        echoSocket = startEchoServer()
        serverKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        expectedFingerprint = rsaFingerprint(serverKeyPair.public as RSAPublicKey)
        sshd = SshServer.setUpDefaultServer().apply {
            host = LOOPBACK
            port = freePort()
            keyPairProvider = KeyPairProvider.wrap(serverKeyPair)
            publickeyAuthenticator = PublickeyAuthenticator { _, key, _ ->
                authAttempts.incrementAndGet()
                events.add("auth")
                wireBlob(key).contentEquals(userPublicBlob)
            }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
    }

    @AfterEach
    fun tearDown() {
        if (this::sshd.isInitialized && sshd.isStarted) sshd.stop(true)
        if (this::echoSocket.isInitialized) echoSocket.close()
        if (this::echoThread.isInitialized) echoThread.join(5_000)
    }

    @Test
    fun `open forwards bytes from localPort to server-side echo socket`() = runTest {
        val handle = SshjTunnel().open(spec(), HostKeyDecision { true })

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), handle.localPort), 10_000)
                socket.soTimeout = 10_000
                val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
                socket.getOutputStream().apply {
                    write(payload)
                    flush()
                }
                val echoed = ByteArray(payload.size)
                DataInputStream(socket.getInputStream()).readFully(echoed)
                assertArrayEquals(payload, echoed)
            }
        } finally {
            handle.close()
            handle.awaitClosed()
        }
    }

    @Test
    fun `open rejects unknown host key when decision returns false`() = runTest {
        val fingerprints = mutableListOf<String>()

        assertThrows(TransportException::class.java) {
            runBlocking {
                SshjTunnel().open(spec(), HostKeyDecision { fingerprint -> fingerprints.add(fingerprint); false })
            }
        }

        assertEquals(listOf(expectedFingerprint), fingerprints)
        assertEquals(0, authAttempts.get())
    }

    @Test
    fun `open succeeds when decision approves TOFU first use`() = runTest {
        val fingerprints = mutableListOf<String>()

        val handle = SshjTunnel().open(spec(), HostKeyDecision { fingerprint ->
            fingerprints.add(fingerprint)
            events.add("decision")
            true
        })

        try {
            assertEquals(listOf(expectedFingerprint), fingerprints)
            assertTrue(FINGERPRINT_REGEX.matches(fingerprints.single()))
            assertEquals("decision", events.first())
            assertTrue(events.contains("auth"))
            assertTrue(authAttempts.get() >= 1)
        } finally {
            handle.close()
            handle.awaitClosed()
        }
    }

    @Test
    fun `close tears down localPort`() = runTest {
        val handle = SshjTunnel().open(spec(), HostKeyDecision { true })
        val localPort = handle.localPort

        handle.close()
        handle.awaitClosed()

        // The tunnel must have released its listener: the same loopback port can
        // be bound again. (Asserting that a connect() is refused instead is racy
        // on a shared host, where an unrelated process may grab the ephemeral
        // port the instant we release it.)
        val probe = ServerSocket()
        probe.reuseAddress = true
        try {
            probe.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), localPort))
        } catch (e: java.net.BindException) {
            assertTrue(false, "local port $localPort was not released after close: ${e.message}")
        } finally {
            probe.close()
        }
    }

    @Test
    fun `ssh-exec relay pipes bytes through the remote command`() = runTest {
        val script = writeFakeServerScript()
        val handle = SshjTunnel().open(execSpec(script.absolutePath), HostKeyDecision { true })

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), handle.localPort), 10_000)
                socket.soTimeout = 10_000
                val payload = "hello from the local unison".toByteArray()
                socket.getOutputStream().apply {
                    write(payload)
                    flush()
                }
                socket.shutdownOutput()
                val echoed = socket.getInputStream().readBytes()
                assertArrayEquals(payload, echoed)
            }
        } finally {
            handle.close()
            handle.awaitClosed()
        }
    }

    @Test
    fun `ssh-exec relay tears down the local port on close`() = runTest {
        val script = writeFakeServerScript()
        val handle = SshjTunnel().open(execSpec(script.absolutePath), HostKeyDecision { true })
        val localPort = handle.localPort

        handle.close()
        handle.awaitClosed()

        val probe = ServerSocket()
        probe.reuseAddress = true
        try {
            // The JDK releases a closed ServerSocketChannel's fd asynchronously,
            // so allow a brief window rather than asserting instant release.
            var released = false
            val deadline = System.currentTimeMillis() + 2_000
            while (!released && System.currentTimeMillis() < deadline) {
                released = try {
                    probe.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), localPort))
                    true
                } catch (_: java.net.BindException) {
                    Thread.sleep(25)
                    false
                }
            }
            assertTrue(released, "local port $localPort was not released after close")
        } finally {
            probe.close()
        }
    }

    private fun writeFakeServerScript(): File {
        val script = createTempDirectory("unisondroid-relay").resolve("fake-unison").toFile()
        // Stands in for `unison -server`: echoes the byte stream on stdin.
        script.writeText("#!/bin/sh\nexec cat\n")
        script.setExecutable(true)
        return script
    }

    private fun spec() = TunnelSpec(
        host = LOOPBACK,
        port = sshd.port,
        user = "sync",
        privateKeyPem = userKeyPem,
        remoteSocketPort = echoSocket.localPort,
        transport = Transport.SOCKET,
    )

    private fun execSpec(serverCommand: String) = TunnelSpec(
        host = LOOPBACK,
        port = sshd.port,
        user = "sync",
        privateKeyPem = userKeyPem,
        transport = Transport.SSH_EXEC,
        serverCommand = serverCommand,
    )

    private fun startEchoServer(): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK))
        echoThread = thread(isDaemon = true, name = "echo-${server.localPort}") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true, name = "echo-conn") {
                    client.use { socket ->
                        val buffer = ByteArray(8192)
                        try {
                            while (true) {
                                val n = socket.getInputStream().read(buffer)
                                if (n < 0) break
                                socket.getOutputStream().apply {
                                    write(buffer, 0, n)
                                    flush()
                                }
                            }
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
        return server
    }

    private fun rsaFingerprint(public: RSAPublicKey): String {
        val blob = Buffer.PlainBuffer()
            .putString("ssh-rsa")
            .putMPInt(public.publicExponent)
            .putMPInt(public.modulus)
            .compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun wireBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val FINGERPRINT_REGEX = Regex("SHA256:[A-Za-z0-9+/]{43}")
    }
}
