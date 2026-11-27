package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException

internal data class AdminLongPrepareIntent(
    val title: String,
    val speaker: String,
    val description: String,
    val categoryId: String,
    val subcategoryId: String,
    val expectedCategoryName: String,
    val expectedSubcategoryName: String,
) {
    fun fields() = listOf(title, speaker, description, categoryId, subcategoryId, expectedCategoryName, expectedSubcategoryName)

    fun validate() {
        val ids = Regex("[A-Za-z0-9_-]{1,128}")
        fun safe(s: String, max: Int) = s.length <= max && s.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }
        if (title.isBlank() || title != title.trim() || !safe(title, 400) || !safe(speaker, 160) ||
            !safe(description, 2000) || !ids.matches(categoryId) || !ids.matches(subcategoryId) ||
            expectedCategoryName.isBlank() || !safe(expectedCategoryName, 400) ||
            expectedSubcategoryName.isBlank() || !safe(expectedSubcategoryName, 400))
            throw IOException("Invalid private preparation details")
    }

    fun encode(assembly: AdminLongAssemblyReceipt): ByteArray {
        validate()
        if (assembly.totalBytes !in 1..MAX_ORIGINAL_BYTES ||
            !Regex("[a-f0-9]{64}").matches(assembly.wholeSha256))
            throw IOException("Invalid private preparation source")
        val keys = listOf("title","speaker","description","categoryId","subcategoryId",
            "expectedCategoryName","expectedSubcategoryName")
        val bytes = buildString {
            append('{')
            for ((index, value) in fields().withIndex()) {
                if (index > 0) append(',')
                appendQuoted(keys[index])
                append(':')
                appendQuoted(value)
            }
            append(",\"totalBytes\":${assembly.totalBytes},\"wholeSha256\":")
            appendQuoted(assembly.wholeSha256)
            append('}')
        }.encodeToByteArray()
        if (bytes.size !in 1..MAX_LONG_STAGE_RESPONSE) throw IOException("Private preparation details too large")
        return bytes
    }
}

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> when {
                c.isHighSurrogate() -> {
                    val next = value.getOrNull(i)
                    if (next == null || !next.isLowSurrogate()) throw IOException("Invalid private preparation text")
                    append(c).append(next)
                    i++
                }
                c.isLowSurrogate() || c.code < 32 -> throw IOException("Invalid private preparation text")
                else -> append(c)
            }
        }
    }
    append('"')
}

internal data class AdminLongPreparationReceipt(val draftId: String, val totalBytes: Long, val wholeSha256: String)

internal fun interface AdminLongPrepareWire {
    suspend fun prepare(assembly: AdminLongAssemblyReceipt, intent: AdminLongPrepareIntent, guard: () -> Unit): AdminLongPreparationReceipt
}
