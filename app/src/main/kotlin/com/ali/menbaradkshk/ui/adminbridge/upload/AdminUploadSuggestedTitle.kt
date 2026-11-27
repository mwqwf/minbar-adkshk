package com.ali.menbaradkshk.ui.adminbridge.upload

import java.net.URLDecoder

private val audioSuffix = Regex("""\.(opus|ogg|mp3|m4a|aac|wav|flac|amr|wma|webm|mka|3gp|aif|aiff|midi|mid)$""", RegexOption.IGNORE_CASE)
private val automationName = Regex(
    """^(?:(?:AUD|VID|PTT|IMG|DOC|GIF)[-_]\p{Nd}{8}[-_]WA\p{Nd}+.*|WhatsApp\s+(?:Audio|Video|Ptt|Image)\b.*|(?:voice|audio|video|sound|note|memo|track|file|item|untitled|rec|record(?:ing)?|mic|zoom|تسجيل(?:\s+صوتي)?|مقطع|ملف|صوت|بدون\s*عنوان)(?:[\p{Nd}\s_.–—-]*)|[\p{Nd}\s_.()–—-]+)$""",
    RegexOption.IGNORE_CASE,
)
private val technicalNote = Regex("""\b(?:mp3|m4a|opus|wav|aac|flac|kbps|youtube|download|official)\b|نسخة|تحميل|بجودة""", RegexOption.IGNORE_CASE)
private val brackets = Regex("""[\[({]([^\])}]*)[\])}]""")
private val sequencePrefix = Regex("""^\s*[\[(]?\p{Nd}{1,4}[\])]?\s*(?:[-_.–—]+\s*|\s+)""")
private val domain = Regex("""(?:^|\s)(?:www\.)?[A-Za-z0-9.-]+\.(?:com|net|org|info|me|tv|cc)(?=\s|$)""", RegexOption.IGNORE_CASE)

/** A local, optional suggestion. Do not copy provider paths, hidden formatting, or recorder defaults. */
internal fun suggestAdminLessonTitle(fileName: String): String? {
    if (fileName.length > 120) return null // Limit work independently of the draft-name sanitizer.
    val original = fileName.trim()
    val decoded = if ('%' !in original) original else runCatching {
        // Android's DISPLAY_NAME isn't a form field: a literal plus is legitimate title text.
        URLDecoder.decode(original.replace("+", "%2B"), "UTF-8")
    }.getOrNull() ?: return null
    if (decoded.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() ||
            Character.getType(it) == Character.SURROGATE.toInt() || it == '/' || it == '\\' ||
            it == '\u2028' || it == '\u2029' || it == '\uFFFD' }) return null
    val suffix = audioSuffix.find(decoded) ?: return null
    val stem = decoded.substring(0, suffix.range.first).trim()
    if (automationName.matches(stem)) return null
    val clean = brackets.replace(stem) { if (technicalNote.containsMatchIn(it.groupValues[1])) " " else it.value }
        .replace(domain, " ")
        .replace(sequencePrefix, "")
        .replace(Regex("[_~•·]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim { it.isWhitespace() || it in "-–—ـ_.,،؛;:" }
    return clean.takeIf { it.length in 2..120 && it.count(Char::isLetter) >= 2 && !automationName.matches(it) }
}
