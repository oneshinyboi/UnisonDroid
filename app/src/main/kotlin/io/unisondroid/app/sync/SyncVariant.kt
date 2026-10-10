package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile

/**
 * A per-run choice of how a profile is synced, independent of the profile's stored settings.
 *
 * [TWO_WAY] is the normal two-directional Unison run. The one-way variants turn Unison into a
 * mirror in one direction; the COPY variants additionally keep files that exist only on the
 * target (`nodeletion`), while the MIRROR variants make the target an exact copy. [TEST_CONNECTION]
 * connects to the server and exits without touching either replica.
 */
enum class SyncVariant {
    TWO_WAY,
    COPY_TO_SERVER,
    MIRROR_TO_SERVER,
    COPY_FROM_SERVER,
    MIRROR_FROM_SERVER,
    TEST_CONNECTION;

    /** One-way variants turn the run into a mirror and override the profile's conflict policy. */
    val isOneWay: Boolean
        get() = this == COPY_TO_SERVER || this == MIRROR_TO_SERVER ||
            this == COPY_FROM_SERVER || this == MIRROR_FROM_SERVER

    /** Mirror variants delete files that exist only on the target; copies leave them alone. */
    val destroysTarget: Boolean
        get() = this == MIRROR_TO_SERVER || this == MIRROR_FROM_SERVER

    /** Diagnostic runs connect and exit without syncing and must not record a sync result. */
    val isDiagnostic: Boolean
        get() = this == TEST_CONNECTION

    /**
     * Unison preference lines that implement this variant. The remote root is named using the same
     * `ssh://` form as [PrfGenerator.sshRoot] so Unison resolves it to the same replica.
     */
    fun preferences(profile: Profile): List<String> = when (this) {
        TWO_WAY, TEST_CONNECTION -> emptyList()
        COPY_TO_SERVER -> listOf(
            "force = ${profile.localRoot}",
            "nodeletion = ${PrfGenerator.sshRoot(profile)}",
        )

        MIRROR_TO_SERVER -> listOf("force = ${profile.localRoot}")
        COPY_FROM_SERVER -> listOf(
            "force = ${PrfGenerator.sshRoot(profile)}",
            "nodeletion = ${profile.localRoot}",
        )

        MIRROR_FROM_SERVER -> listOf("force = ${PrfGenerator.sshRoot(profile)}")
    }

    /** Extra command-line arguments passed after the profile name. */
    fun args(): List<String> = if (isDiagnostic) listOf(TEST_SERVER_ARG) else emptyList()

    private companion object {
        const val TEST_SERVER_ARG = "-testserver"
    }
}
