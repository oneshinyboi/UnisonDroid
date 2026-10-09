package io.unisondroid.app

import io.unisondroid.app.sync.SshCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.Security
import javax.crypto.KeyAgreement

class SshCryptoTest {

    @Test
    fun bundledBouncyCastleProvidesX25519() {
        SshCrypto.ensureInstalled()

        // Android registers its own (old) BouncyCastle under the name "BC"
        // (class com.android.org.bouncycastle...). After install, "BC" must be
        // the bundled org.bouncycastle implementation, and X25519 must resolve.
        val provider = Security.getProvider("BC")
        assertNotNull(provider)
        assertEquals(
            "org.bouncycastle.jce.provider.BouncyCastleProvider",
            provider!!.javaClass.name,
        )
        assertNotNull(KeyAgreement.getInstance("X25519", "BC"))
    }
}
