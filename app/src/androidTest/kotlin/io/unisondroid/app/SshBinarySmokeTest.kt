package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.sync.ProcessSshTool
import io.unisondroid.app.sync.SshToolException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SshBinarySmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun nativeFile(name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name)

    private fun tool() = ProcessSshTool(
        keygen = nativeFile("libssh-keygen.so"),
        keyscan = nativeFile("libssh-keyscan.so"),
        workDir = context.cacheDir,
    )

    @Test
    fun ssh_binary_reports_its_version() {
        val process = ProcessBuilder(nativeFile("libssh.so").absolutePath, "-V")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)
        assertTrue("output was: $output", output.contains("OpenSSH_10.2"))
    }

    @Test
    fun keygen_generates_and_derives_round_trip() {
        val generated = tool().generateKey("smoke")

        assertTrue(generated.privatePem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue("public line was: ${generated.publicKeyLine}", generated.publicKeyLine.startsWith("ssh-ed25519 "))

        val derived = tool().derivePublicKey(generated.privatePem)
        assertTrue("derived was: $derived", derived.startsWith("ssh-ed25519 "))
    }

    @Test
    fun keyscan_against_a_dead_port_reports_no_keys() {
        val port = ServerSocket(0).use { it.localPort }

        val result = runCatching { tool().scanHostKeys("127.0.0.1", port) }

        assertTrue(
            "expected an SshToolException or an empty key list, got: $result",
            result.isFailure || result.getOrNull().isNullOrEmpty(),
        )
    }

    @Test
    fun derive_rejects_a_malformed_key() {
        val failure = runCatching { tool().derivePublicKey("this is not a key") }
        assertTrue("expected SshToolException, got: $failure", failure.exceptionOrNull() is SshToolException)
    }
}
