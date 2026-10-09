package io.unisondroid.app.sync

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * sshj resolves JCE algorithms by provider *name* ("BC"). Android pre-registers
 * its own old, stripped BouncyCastle under that name, which lacks modern
 * algorithms such as X25519, so key exchange fails with
 * "no such algorithm X25519 for provider BC". Install the bundled modern
 * BouncyCastle under the same name before any SSH session is created.
 */
object SshCrypto {

    private const val BC = "BC"

    @Volatile
    private var installed = false

    @Synchronized
    fun ensureInstalled() {
        if (installed) return
        // Replace any platform-registered "BC" with the bundled implementation.
        Security.removeProvider(BC)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        SecurityUtils.setSecurityProvider(BC)
        installed = true
    }
}
