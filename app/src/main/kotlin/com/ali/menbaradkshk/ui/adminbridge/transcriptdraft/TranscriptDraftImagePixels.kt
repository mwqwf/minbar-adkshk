package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val maxSourceBytes = 8 * 1024 * 1024
private const val maxSourceAxis = 8192
private const val maxSourcePixels = 24_000_000L

/** Reads and reconstructs only explicitly selected owned pixels. Never forwards the source bytes. */
internal fun prepareDraftImagePixels(open: () -> InputStream, checkActive: () -> Unit): OwnedDraftJpeg {
    checkActive()
    val original = open().use { source -> readDraftImageBytes(source, maxSourceBytes, checkActive) }
    try {
        checkActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w in 1..maxSourceAxis && h in 1..maxSourceAxis && w.toLong() * h <= maxSourcePixels)
        var sample = 1
        while (!validDraftImageShape((w + sample - 1) / sample, (h + sample - 1) / sample)) sample *= 2
        checkActive()
        val pixels = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Image decode failed")
        try {
            require(validDraftImageShape(pixels.width, pixels.height))
            checkActive()
            val clean = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
            try {
                clean.setHasAlpha(false)
                val canvas = Canvas(clean)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(pixels, null, Rect(0, 0, clean.width, clean.height), Paint(Paint.FILTER_BITMAP_FLAG))
                for (quality in listOf(85, 72, 58, 43, 30)) {
                    checkActive()
                    val output = LimitedJpegOutput()
                    val encoded = runCatching {
                        check(clean.compress(Bitmap.CompressFormat.JPEG, quality, output))
                        output.bytes()
                    }.getOrNull() ?: continue
                    try {
                        val minimal = runCatching { stripFreshJpegMetadata(encoded) }.getOrNull() ?: continue
                        checkActive()
                        return OwnedDraftJpeg(minimal).also { minimal.fill(0) }
                    } finally {
                        encoded.fill(0)
                    }
                }
                throw IOException("No compatible image within 256 KiB")
            } finally {
                clean.recycle()
            }
        } finally {
            pixels.recycle()
        }
    } finally {
        original.fill(0)
    }
}

internal fun readDraftImageBytes(input: InputStream, cap: Int, checkActive: () -> Unit): ByteArray {
    require(cap in 1..maxSourceBytes)
    val output = ByteArrayOutputStream()
    val scratch = ByteArray(16 * 1024)
    while (true) {
        checkActive()
        val read = input.read(scratch, 0, minOf(scratch.size, cap - output.size() + 1))
        if (read < 0) break
        check(read in 1..(cap - output.size()))
        output.write(scratch, 0, read)
    }
    check(output.size() > 0)
    checkActive()
    return output.toByteArray()
}

private class LimitedJpegOutput : OutputStream() {
    private val output = ByteArrayOutputStream()
    override fun write(b: Int) {
        check(output.size() < MAX_DRAFT_JPEG_BYTES * 2)
        output.write(b)
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        check(len <= MAX_DRAFT_JPEG_BYTES * 2 - output.size())
        output.write(b, off, len)
    }
    fun bytes() = output.toByteArray()
}
