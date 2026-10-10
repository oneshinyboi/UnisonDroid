package io.unisondroid.app.sync

data class BundledUnison(val version: String, val fileName: String)

object UnisonInfo {
    val BUNDLED: List<BundledUnison> = listOf(
        BundledUnison("2.54.0", "libunison_2_54_0.so"),
        BundledUnison("2.53.8", "libunison_2_53_8.so"),
    )
    val DEFAULT: BundledUnison = BUNDLED.first()

    fun forVersion(version: String): BundledUnison =
        BUNDLED.firstOrNull { it.version == version } ?: DEFAULT
}
