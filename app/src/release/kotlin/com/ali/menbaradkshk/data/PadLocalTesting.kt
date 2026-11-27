package com.ali.menbaradkshk.data

/**
 * 🔒 Release: never. (Debug sets this to true to let) `bundletool install-apks --local-testing` deliver the asset packs without a
 * Play Store install source. This file exists only in the debug source set; the release source set
 * pins it to false, so no release artifact can carry the override.
 */
internal object PadLocalTesting {
    const val ENABLED = false
}
