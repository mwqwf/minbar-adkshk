package com.ali.menbaradkshk.media

/**
 * Never grant ordinary player/session commands based on a package name or connection hints.
 * Media3 computes trust from the platform, and the verified UID keeps our own controller working.
 */
internal object PlaybackControllerAccess {
    fun canConnect(appUid: Int, controllerUid: Int, trustedForMediaControl: Boolean): Boolean =
        trustedForMediaControl || (appUid >= 0 && controllerUid == appUid)

    /** The service factory also receives placeholders for legacy lookups and hardware resumption. */
    fun canReturnSession(
        appUid: Int,
        controllerUid: Int,
        trustedForMediaControl: Boolean,
        verifiedController: Boolean,
    ): Boolean = !verifiedController || canConnect(appUid, controllerUid, trustedForMediaControl)
}
