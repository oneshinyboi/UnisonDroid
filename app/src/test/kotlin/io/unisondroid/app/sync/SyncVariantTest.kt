package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncVariantTest {

    private val profile = Profile(
        id = "p1",
        name = "Phone",
        localRoot = "/storage/emulated/0/Sync",
        remoteRoot = "/srv/sync",
        host = "server.example",
        user = "syncuser",
        sshKeyId = "key-1",
    )

    @Test
    fun `two way adds no preferences or arguments`() {
        assertEquals(emptyList<String>(), SyncVariant.TWO_WAY.preferences(profile))
        assertEquals(emptyList<String>(), SyncVariant.TWO_WAY.args())
    }

    @Test
    fun `mirror to server forces the local root`() {
        assertEquals(
            listOf("force = /storage/emulated/0/Sync"),
            SyncVariant.MIRROR_TO_SERVER.preferences(profile),
        )
    }

    @Test
    fun `copy to server forces local root and forbids deletions on the server`() {
        assertEquals(
            listOf(
                "force = /storage/emulated/0/Sync",
                "nodeletion = ssh://syncuser@server.example//srv/sync",
            ),
            SyncVariant.COPY_TO_SERVER.preferences(profile),
        )
    }

    @Test
    fun `mirror from server forces the ssh root`() {
        assertEquals(
            listOf("force = ssh://syncuser@server.example//srv/sync"),
            SyncVariant.MIRROR_FROM_SERVER.preferences(profile),
        )
    }

    @Test
    fun `copy from server forces remote and forbids deletions on the phone`() {
        assertEquals(
            listOf(
                "force = ssh://syncuser@server.example//srv/sync",
                "nodeletion = /storage/emulated/0/Sync",
            ),
            SyncVariant.COPY_FROM_SERVER.preferences(profile),
        )
    }

    @Test
    fun `test connection passes testserver and no preferences`() {
        assertEquals(emptyList<String>(), SyncVariant.TEST_CONNECTION.preferences(profile))
        assertEquals(listOf("-testserver"), SyncVariant.TEST_CONNECTION.args())
    }

    @Test
    fun `only mirror variants are destructive`() {
        assertTrue(SyncVariant.MIRROR_TO_SERVER.destroysTarget)
        assertTrue(SyncVariant.MIRROR_FROM_SERVER.destroysTarget)
        assertFalse(SyncVariant.COPY_TO_SERVER.destroysTarget)
        assertFalse(SyncVariant.COPY_FROM_SERVER.destroysTarget)
        assertFalse(SyncVariant.TWO_WAY.destroysTarget)
        assertFalse(SyncVariant.TEST_CONNECTION.destroysTarget)
    }

    @Test
    fun `one way variants are flagged and diagnostics are not`() {
        assertTrue(SyncVariant.COPY_TO_SERVER.isOneWay)
        assertTrue(SyncVariant.MIRROR_TO_SERVER.isOneWay)
        assertTrue(SyncVariant.COPY_FROM_SERVER.isOneWay)
        assertTrue(SyncVariant.MIRROR_FROM_SERVER.isOneWay)
        assertFalse(SyncVariant.TWO_WAY.isOneWay)
        assertFalse(SyncVariant.TEST_CONNECTION.isOneWay)

        assertTrue(SyncVariant.TEST_CONNECTION.isDiagnostic)
        assertFalse(SyncVariant.TWO_WAY.isDiagnostic)
    }
}
