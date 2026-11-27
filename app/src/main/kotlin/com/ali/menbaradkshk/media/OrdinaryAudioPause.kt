package com.ali.menbaradkshk.media

import android.os.Looper

/**
 * In-process hand-off used by the admin private preview: pause (never stop or resume) the
 * ordinary lesson/Quran player before a private review takes audio focus. Main thread only.
 * No player running means nothing to pause, which counts as success.
 */
internal object OrdinaryAudioPause {
    @Volatile private var hook: (() -> Unit)? = null

    fun register(pause: () -> Unit) { hook = pause }
    fun unregister(pause: () -> Unit) { if (hook === pause) hook = null }

    fun pauseNow(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = hook ?: return true
        return runCatching { current() }.isSuccess
    }
}
