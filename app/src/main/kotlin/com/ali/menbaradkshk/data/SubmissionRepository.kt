package com.ali.menbaradkshk.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** الفشل العابر (شبكة/مهلة/5xx) يُعاد بعده الإرسال؛ ما عداه خطأ دائم. */
internal fun isTransientFailure(failure: Throwable): Boolean {
    val api = failure as? MinbarApi.ApiException ?: failure.cause as? MinbarApi.ApiException
    if (api != null) return api.code >= 500 || api.code == 429 || api.code == 408
    return generateSequence(failure) { it.cause }.take(5).any { it is java.io.IOException }
}

/** Explicit account-data erasure only; no public submission read/write or retries. */
class SubmissionRepository private constructor(context: Context) {
    private val appContext = context.applicationContext

    /** حذف كل ما يخصّ هذا الجهاز على الخادم (مساهمات ورسائل ومرفقات). */
    suspend fun deleteCloudIdentityData() {
        // ⚠️ على IO: «حذف بياناتي» يُنادى من `viewModelScope` (الخيط الرئيسي)،
        // فكان الحذف السحابيّ يفشل دائماً بـ`NetworkOnMainThreadException`.
        withContext(Dispatchers.IO) {
            MinbarApi.deleteUser(appContext, "/v1/me")
        }
    }

    companion object {
        @Volatile private var instance: SubmissionRepository? = null
        fun get(context: Context): SubmissionRepository = instance ?: synchronized(this) {
            instance ?: SubmissionRepository(context).also { instance = it }
        }
    }
}
