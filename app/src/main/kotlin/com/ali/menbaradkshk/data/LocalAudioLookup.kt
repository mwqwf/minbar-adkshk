package com.ali.menbaradkshk.data

import java.io.File

/** ملفّ نملكه، أو صوت من حزمة Play يلزم إعادة حل موقعه عند فتحه. */
internal data class LocalAudio(val path: String, val playSha: String? = null)

internal fun isServingAudioSha(sha: String): Boolean =
    sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }
