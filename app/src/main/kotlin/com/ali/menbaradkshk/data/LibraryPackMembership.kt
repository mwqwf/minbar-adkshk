package com.ali.menbaradkshk.data

import java.io.InputStream

internal object LibraryPackMembership {
    const val PATH = "catalog/pad-membership.v1"
    private const val HEADER = "MINBAR-PAD-MEMBERSHIP-1\n"
    private const val MAX_BYTES = 512 * 1024
    private val row = Regex("([0-9a-f]{64}) (core|rest|more)")

    fun read(input: InputStream, configured: Collection<String>, catalog: Set<String>): Map<String, String>? {
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val n = input.read(bytes, size, bytes.size - size)
            if (n < 0) break
            if (n == 0) return null
            size += n
        }
        if (size > MAX_BYTES || (0 until size).any { bytes[it] != 10.toByte() && bytes[it].toInt() !in 32..126 }) return null
        val content = String(bytes, 0, size, Charsets.US_ASCII)
        if (!content.startsWith(HEADER) || !content.endsWith('\n')) return null
        val entries = linkedMapOf<String, String>()
        var previous = ""
        for (line in content.removePrefix(HEADER).dropLast(1).lineSequence()) {
            val (_, sha, pack) = row.matchEntire(line)?.groupValues ?: return null
            if (pack !in configured || sha <= previous) return null
            entries[sha] = pack
            previous = sha
        }
        return entries.takeIf { it.keys == catalog && it.values.toSet() == configured.toSet() }
    }
}
