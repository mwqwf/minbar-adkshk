package com.ali.menbaradkshk.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.ResolvingDataSource
import com.ali.menbaradkshk.data.LocalAudio
import com.ali.menbaradkshk.data.isServingAudioSha
import java.io.File

internal object PlayPackAudioSource {
    private const val SCHEME = "minbar-pad"
    internal fun handles(uri: Uri?): Boolean = uri?.scheme == SCHEME

    internal fun uri(lessonId: String, local: LocalAudio): Uri = local.playSha?.let { sha ->
        Uri.Builder().scheme(SCHEME).authority("lesson").appendPath(lessonId).appendPath(sha).build()
    } ?: Uri.fromFile(File(local.path))

    /** لا يُخزّن العنوان الفعلي داخل قائمة التشغيل؛ يعيد Media3 حلّه قبل كل فتح. */
    @OptIn(markerClass = [UnstableApi::class])
    internal fun factory(upstream: DataSource.Factory, find: (String) -> LocalAudio?): DataSource.Factory =
        ResolvingDataSource.Factory(upstream) { spec ->
            val requested = spec.uri
            if (!handles(requested)) return@Factory spec
            val parts = requested.pathSegments
            val sha = parts.getOrNull(1)
            val local = if (requested.authority == "lesson" && parts.size == 2 &&
                sha != null && isServingAudioSha(sha)) find(parts[0])?.takeIf { it.playSha == sha } else null
            if (local == null) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            spec.withUri(Uri.fromFile(File(local.path)))
        }
}
