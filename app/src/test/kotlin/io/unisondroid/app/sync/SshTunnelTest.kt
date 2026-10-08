package io.unisondroid.app.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.TransportException
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
        val (pem, publicBlob) = generateEd25519KeyPem()
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

        assertThrows(IOException::class.java) {
            Socket().connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), localPort), 2_000)
        }
    }

    private fun spec() = TunnelSpec(
        host = LOOPBACK,
        port = sshd.port,
        user = "sync",
        privateKeyPem = userKeyPem,
        remoteSocketPort = echoSocket.localPort,
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

    private fun generateEd25519KeyPem(): Pair<String, ByteArray> {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = keyPair.public as EdECPublicKey
        val seed = (keyPair.private as EdECPrivateKey).bytes.orElseThrow()

        val pubBytes = ByteArray(32)
        val yBytes = pub.point.y.toByteArray()
        for (index in yBytes.indices) {
            if (index < 32) pubBytes[index] = yBytes[yBytes.size - 1 - index]
        }
        if (pub.point.isXOdd) {
            pubBytes[31] = (pubBytes[31].toInt() or 0x80).toByte()
        }

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
            .putString("unisondroid-test")
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
        const val CHECK_INT = 1_234_567_890L
        val FINGERPRINT_REGEX = Regex("SHA256:[A-Za-z0-9+/]{43}")
    }
}
