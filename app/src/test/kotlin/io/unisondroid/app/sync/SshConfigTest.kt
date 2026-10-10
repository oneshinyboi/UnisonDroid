package io.unisondroid.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SshConfigTest {

    @Test
    fun `render emits a single Host block with the run paths`() {
        val out = SshConfig.render(File("/ssh/key-1"), File("/ssh/known_hosts"), 2222)
        assertEquals(
            "Host *\n" +
                "  IdentityFile /ssh/key-1\n" +
                "  UserKnownHostsFile /ssh/known_hosts\n" +
                "  GlobalKnownHostsFile /dev/null\n" +
                "  StrictHostKeyChecking yes\n" +
                "  BatchMode yes\n" +
                "  IdentitiesOnly yes\n" +
                "  LogLevel ERROR\n" +
                "  Port 2222\n",
            out,
        )
    }
}
