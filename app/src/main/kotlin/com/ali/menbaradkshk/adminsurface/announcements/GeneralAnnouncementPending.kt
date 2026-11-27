package com.ali.menbaradkshk.adminsurface.announcements

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

// Only the exact confirmed draft and stable operation ID are held. Never store the signed
// confirmation capsule or bearer. A matching owner can re-review after process death.
internal class GeneralNoticePending(context: Context, accountKey: String) {
    private val file: AtomicFile

    init {
        require(Regex("[a-f0-9]{64}").matches(accountKey))
        file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "minbar-general-notice-$accountKey.json"))
    }

    fun read(): GeneralNoticeIntent? = synchronized(lock) { readLocked() }

    fun begin(intent: GeneralNoticeIntent) = synchronized(lock) {
        val prior = readLocked()
        check(prior == null || prior == intent) { "A prior notice is awaiting confirmation" }
        if (prior != null) return@synchronized
        val stream = file.startWrite()
        try {
            stream.write(intent.wire())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        // Android AtomicFile may log a rename failure without throwing. Never start
        // the request unless the exact operation is readable as the committed record.
        check(readLocked() == intent)
    }

    fun clear(intent: GeneralNoticeIntent) = synchronized(lock) {
        val prior = readLocked()
        check(prior == null || prior == intent)
        if (prior != null) {
            file.delete()
            check(readLocked() == null)
        }
    }

    private fun readLocked(): GeneralNoticeIntent? {
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return null
        val bytes = file.openRead().use {
            require(it.channel.size() in 1..GENERAL_NOTICE_WIRE_MAX.toLong())
            it.readBytes()
        }
        require(bytes.size in 1..GENERAL_NOTICE_WIRE_MAX)
        val entry = JSONObject(bytes.toString(Charsets.UTF_8))
        return GeneralNoticeIntent(entry.getString("operationId"), entry.getString("title"), entry.getString("body"))
            .also { require(it.wire().contentEquals(bytes)) }
    }

    private companion object {
        val lock = Any()
    }
}
