package com.ali.menbaradkshk.data

import java.io.File
import java.security.MessageDigest

private val supportFileName = Regex("[A-Za-z0-9._-]{1,116}\\.m4a")
private val deviceIdPattern = Regex("[A-Za-z0-9_:-]{8,80}")

internal data class SupportUploadReceipt(
    val accepted: Any?,
    val key: Any?,
    val sha256: Any?,
    val sizeBytes: Any?,
)

internal data class SupportPostReceipt(val accepted: Any?, val threadId: Any? = null)

/**
 * A 2xx status is not enough to discard an outbox entry. Confirm that the
 * upload identifies the intended file, then require the message acknowledgement.
 */
internal fun verifySupportMessageDelivery(
    audio: File?,
    deviceId: String,
    filename: String,
    newThreadId: String?,
    upload: (File, String) -> SupportUploadReceipt,
    post: (audioKey: String?) -> SupportPostReceipt,
) {
    val audioKey = audio?.let { file ->
        require(deviceIdPattern.matches(deviceId) && supportFileName.matches(filename))
        val expectedSize = file.length()
        require(file.isFile && expectedSize in 1..SUPPORT_AUDIO_MAX_BYTES) { "المرفق مفقود أو يتجاوز الحجم المسموح." }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytesRead = file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= expectedSize) { "تغيّر المرفق قبل إرساله." }
                digest.update(buffer, 0, count)
            }
            total
        }
        require(bytesRead == expectedSize) { "تغيّر المرفق قبل إرساله." }
        val expectedHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val result = upload(file, filename)
        val key = result.key as? String
        val canonicalKey = Regex("user/${Regex.escape(deviceId)}/support/[0-9]{1,19}_${Regex.escape(filename)}")
        require(result.accepted == true && key != null && canonicalKey.matches(key) &&
            result.sha256 == expectedHash && (result.sizeBytes is Int || result.sizeBytes is Long) &&
            (result.sizeBytes as Number).toLong() == expectedSize
        ) { "لم يؤكد الخادم استلام المرفق." }
        key
    }
    val result = post(audioKey)
    require(result.accepted == true && (newThreadId == null || result.threadId == newThreadId)) {
        "لم يؤكد الخادم استلام الرسالة."
    }
}
