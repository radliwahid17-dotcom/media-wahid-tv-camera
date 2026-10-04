package tv.mediawahid.camera

enum class WatermarkTemplate(val storageValue: String) {
    DUAL("dual"),
    MEDIA_ONLY("media_only");

    val displayName: String
        get() = when (this) {
            DUAL -> "MASJID + MEDIA WAHID TV"
            MEDIA_ONLY -> "MEDIA WAHID TV"
        }

    val processingLabel: String
        get() = when (this) {
            DUAL -> "Masjid + MEDIA WAHID TV"
            MEDIA_ONLY -> "MEDIA WAHID TV"
        }

    companion object {
        fun fromStorage(value: String?): WatermarkTemplate =
            values().firstOrNull { it.storageValue == value } ?: DUAL
    }
}
