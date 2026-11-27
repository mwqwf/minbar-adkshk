package com.ali.menbaradkshk.ui.adminbridge.upload

private fun ByteArray.b(offset: Int) = this[offset].toInt() and 255
private fun ByteArray.tag(offset: Int, text: String) = offset >= 0 && size - offset >= text.length &&
    text.indices.all { b(offset + it) == text[it].code }
private fun ByteArray.be16(offset: Int) = (b(offset) shl 8) or b(offset + 1)
private fun ByteArray.be32(offset: Int) = (0..3).fold(0L) { v, i -> (v shl 8) or b(offset + i).toLong() }

private data class Box(val name: String, val start: Int, val end: Int)
private class Mp4(private val bytes: ByteArray) {
    private fun boxes(begin: Int, end: Int): List<Box>? {
        if (begin !in 0..end || end > bytes.size) return null
        var offset = begin
        val boxes = ArrayList<Box>()
        while (offset < end) {
            if (end - offset < 8 || boxes.size >= 1024) return null
            val size = bytes.be32(offset)
            if (size !in 8..(end - offset).toLong()) return null // no open-ended or 64-bit boxes in the bounded profile
            val name = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val next = offset + size.toInt()
            boxes += Box(name, offset + 8, next)
            offset = next
        }
        return boxes
    }

    private fun children(parent: Box, skip: Int = 0): List<Box>? = boxes(parent.start + skip, parent.end)
    private fun List<Box>.one(name: String) = singleOrNull { it.name == name }

    fun isLcAudio(): Boolean {
        val root = boxes(0, bytes.size) ?: return false
        val fileType = root.firstOrNull()?.takeIf { it.name == "ftyp" && it.end - it.start in 8..128 && (it.end - it.start) % 4 == 0 } ?: return false
        if (!listOf("M4A ", "mp42", "mp41", "isom", "iso2", "3gp4").any { bytes.tag(fileType.start, it) } ||
            root.any { it.name !in setOf("ftyp", "moov", "mdat", "free", "skip", "wide") } ||
            root.one("ftyp") == null || root.one("mdat")?.let { it.end > it.start } != true) return false
        val movie = children(root.one("moov") ?: return false) ?: return false
        if (movie.any { it.name == "mvex" }) return false // no fragmented track configuration
        val track = children(movie.one("trak") ?: return false) ?: return false
        val media = children(track.one("mdia") ?: return false) ?: return false
        val handler = media.one("hdlr") ?: return false
        if (handler.end - handler.start < 12 || !bytes.tag(handler.start + 8, "soun")) return false
        val mediaInfo = children(media.one("minf") ?: return false) ?: return false
        val dataInfo = children(mediaInfo.one("dinf") ?: return false) ?: return false
        val dataRef = dataInfo.one("dref") ?: return false
        if (dataRef.end - dataRef.start < 8 ||
            bytes.be32(dataRef.start) != 0L || bytes.be32(dataRef.start + 4) != 1L) return false
        val url = children(dataRef, 8)?.singleOrNull()?.takeIf { it.name == "url " } ?: return false
        if (url.end - url.start != 4 || bytes.be32(url.start) != 1L) return false // self-contained url only
        val sampleTable = children(mediaInfo.one("stbl") ?: return false) ?: return false
        val sampleDescription = sampleTable.one("stsd") ?: return false
        if (sampleDescription.end - sampleDescription.start < 8 ||
            bytes.be32(sampleDescription.start) != 0L || bytes.be32(sampleDescription.start + 4) != 1L) return false
        val mp4a = children(sampleDescription, 8)?.singleOrNull()?.takeIf { it.name == "mp4a" } ?: return false
        if (mp4a.end - mp4a.start < 28 || bytes.be16(mp4a.start + 6) != 1 ||
            bytes.be16(mp4a.start + 8) != 0 || bytes.be16(mp4a.start + 16) !in 1..2) return false
        val config = children(mp4a, 28)?.one("esds") ?: return false
        return isLcConfig(config)
    }

    private data class Descriptor(val type: Int, val start: Int, val end: Int)
    private fun descriptor(at: Int, until: Int): Descriptor? {
        if (at !in 0 until until) return null
        val type = bytes.b(at)
        var offset = at + 1
        var count = 0
        var length = 0
        do {
            if (offset >= until || ++count > 4) return null
            val chunk = bytes.b(offset++)
            length = (length shl 7) or (chunk and 127)
        } while (chunk and 128 != 0)
        if (length > until - offset) return null
        return Descriptor(type, offset, offset + length)
    }

    private fun isLcConfig(esds: Box): Boolean {
        if (esds.end - esds.start < 6 || bytes.be32(esds.start) != 0L) return false
        val es = descriptor(esds.start + 4, esds.end) ?: return false
        if (es.type != 3 || es.end != esds.end || es.end - es.start < 3 ||
            bytes.b(es.start + 2) and 0xe0 != 0) return false // no ES URL/dependency/OCR extension
        val codec = descriptor(es.start + 3, es.end) ?: return false
        if (codec.type != 4 || codec.end - codec.start < 13 ||
            bytes.b(codec.start) != 0x40 || bytes.b(codec.start + 1) ushr 2 != 5) return false
        if (codec.end != es.end) {
            val sl = descriptor(codec.end, es.end) ?: return false
            if (sl.type != 6 || sl.end != es.end || sl.end - sl.start != 1 || bytes.b(sl.start) != 2) return false
        }
        val asc = descriptor(codec.start + 13, codec.end) ?: return false
        if (asc.type != 5 || asc.end != codec.end || asc.end - asc.start !in 2..5) return false
        val audioObject = bytes.b(asc.start) ushr 3
        val sampleIndex = ((bytes.b(asc.start) and 7) shl 1) or (bytes.b(asc.start + 1) ushr 7)
        val channels = (bytes.b(asc.start + 1) ushr 3) and 15
        if (audioObject != 2 || sampleIndex !in 0..12 || channels !in 1..2) return false
        // The supported LC writer emits GASpecificConfig's three flags as zero.
        if (bytes.b(asc.start + 1) and 7 != 0) return false
        if (asc.end - asc.start == 2) return true
        // FFmpeg's conventional explicit sync extension declares SBR absent. Reject implicit extras.
        return asc.end - asc.start == 5 && bytes.b(asc.start + 2) == 0x56 &&
            bytes.b(asc.start + 3) == 0xe5 && bytes.b(asc.start + 4) == 0
    }
}

internal fun isStrictMergedLcM4a(bytes: ByteArray) = bytes.size >= 16 && bytes.tag(4, "ftyp") && Mp4(bytes).isLcAudio()

/** Exact contiguous Layer III frame stream, optionally with a bounded ID3 prefix/trailer. */
internal fun isStrictMergedMp3(bytes: ByteArray): Boolean {
    var start = 0
    var end = bytes.size
    if (bytes.tag(0, "ID3")) {
        if (end < 10 || bytes.b(3) !in 2..4 || bytes.b(4) == 255 ||
            (6..9).any { bytes.b(it) >= 128 }) return false
        val size = (6..9).fold(0) { n, i -> (n shl 7) or bytes.b(i) }
        val footer = if (bytes.b(3) == 4 && bytes.b(5) and 16 != 0) 10 else 0
        start = 10 + size + footer
        if (size > 256 * 1024 || start > end || (footer > 0 && !bytes.tag(start - 10, "3DI"))) return false
    }
    if (end - start >= 128 && bytes.tag(end - 128, "TAG")) end -= 128
    var offset = start
    var frames = 0
    var baseline = -1
    val bitrateV1 = intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
    val bitrateV2 = intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160)
    val rates = intArrayOf(44100,48000,32000)
    while (offset < end) {
        if (end - offset < 4 || bytes.b(offset) != 255 || bytes.b(offset + 1) and 0xe0 != 0xe0) return false
        val version = (bytes.b(offset + 1) ushr 3) and 3
        val layer = (bytes.b(offset + 1) ushr 1) and 3
        val index = bytes.b(offset + 2) ushr 4
        val rateIndex = (bytes.b(offset + 2) ushr 2) and 3
        val mono = if (bytes.b(offset + 3) ushr 6 == 3) 1 else 0
        if (version == 1 || layer != 1 || index !in 1..14 || rateIndex == 3 || bytes.b(offset+3) and 3 == 2) return false
        val flags = (version shl 3) or (rateIndex shl 1) or mono
        if (baseline < 0) baseline = flags
        if (baseline != flags) return false
        val v1 = version == 3
        val sampleRate = rates[rateIndex] / if (v1) 1 else if (version == 2) 2 else 4
        val rate = (if (v1) bitrateV1 else bitrateV2)[index]
        val padding = (bytes.b(offset + 2) ushr 1) and 1
        val frameSize = (if (v1) 144_000 else 72_000) * rate / sampleRate + padding
        if (frameSize < 8 || frameSize > end - offset) return false
        offset += frameSize
        frames++
    }
    return frames >= 2 && offset == end
}
