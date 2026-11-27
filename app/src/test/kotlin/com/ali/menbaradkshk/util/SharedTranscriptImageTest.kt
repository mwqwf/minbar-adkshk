package com.ali.menbaradkshk.util

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SharedTranscriptImageTest {
    @Test fun sharedImagesPreserveActualAllowedFormatsAndPassingPreview() = cache { dir ->
        val path = System.getProperty("minbar.imageFixtures")
        val pairs = if (!path.isNullOrBlank()) listOf(
            File(path,"sample.jpg").readBytes() to SharedTranscriptImageType.JPEG,
            File(path,"sample.png").readBytes() to SharedTranscriptImageType.PNG,
            File(path,"sample.webp").readBytes() to SharedTranscriptImageType.WEBP,
        ) else listOf(STRUCTURAL_JPEG to SharedTranscriptImageType.JPEG)
        for ((bytes, expected) in pairs) {
            var inspected = false
            val stored = stageSharedTranscriptImage(dir,10L*1024*1024,{ bytes.inputStream() },{}) { file, detected ->
                assertEquals(expected, detected)
                assertArrayEquals(bytes,file.readBytes())
                inspected = true
                true
            }
            assertTrue(inspected)
            assertEquals(expected.extension,stored.extension)
            assertEquals(expected,ownedSharedTranscriptImageType(dir,stored))
            assertArrayEquals(bytes,stored.readBytes())
            assertNull(ownedSharedTranscriptImageType(File(dir,"different"),stored))
            assertFalse(dir.listFiles()!!.any { it.extension == "part" })
        }
    }

    @Test fun oversizedUnknownSourceDoesNotWriteProbeOrDeletePassingNeighbor() = cache { dir ->
        val keep = File(dir,"older.png").apply { writeText("keep") }
        val cap = 10L*1024*1024
        var offered = 0L
        var written = -1L
        var preview = false
        val source = object : InputStream() {
            override fun read(): Int {
                written = dir.listFiles()!!.single { it != keep }.length()
                return 42
            }
            override fun read(b: ByteArray, offset: Int, length: Int): Int {
                offered += length
                return length
            }
        }
        assertThrows(ContributionMergeSizeExceeded::class.java) {
            stageSharedTranscriptImage(dir,cap,{source},{}) { _,_ -> preview = true; true }
        }
        assertEquals(cap,offered)
        assertEquals(cap,written)
        assertFalse(preview)
        assertEquals(listOf(keep),dir.listFiles()!!.toList())
    }

    @Test fun truncatedUnsupportedOrUndecodableCanNeverBePublishedAsAttachment() = cache { dir ->
        val path = System.getProperty("minbar.imageFixtures")
        val bytes = if (path.isNullOrBlank()) STRUCTURAL_JPEG else File(path,"sample.jpg").readBytes()
        for (invalid in listOf(bytes.copyOf(bytes.size-1), byteArrayOf(), "<svg>not a photo</svg>".encodeToByteArray())) {
            assertThrows(InvalidSharedTranscriptImage::class.java) {
                stageSharedTranscriptImage(dir,128000,{invalid.inputStream()},{}) { _,_ -> fail("Unexpected preview"); false }
            }
        }
        assertThrows(InvalidSharedTranscriptImage::class.java) {
            stageSharedTranscriptImage(dir,128000,{bytes.inputStream()},{}) { _,_ -> false }
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun cancellationAfterReadNeverLeavesAnIncompleteOrRenamedFile() = cache { dir ->
        val original = File(dir,"prior.jpg").apply { writeBytes(STRUCTURAL_JPEG) }
        var cancelled = false
        val source = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, offset: Int, length: Int): Int {
                cancelled = true
                return minOf(length, STRUCTURAL_JPEG.size)
            }
        }
        assertThrows(CancellationException::class.java) {
            stageSharedTranscriptImage(dir,128000,{source},{
                if (cancelled) throw CancellationException("cancel fixture")
            }) { _,_ -> fail("Unexpected preview"); false }
        }
        assertEquals(listOf(original),dir.listFiles()!!.toList())
        assertArrayEquals(STRUCTURAL_JPEG,original.readBytes())
    }

    private inline fun cache(block: (File)-> Unit) {
        val dir = Files.createTempDirectory("minbar-shared-image-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private companion object {
        val STRUCTURAL_JPEG = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()) + ByteArray(9) +
            byteArrayOf(0xff.toByte(),0xd9.toByte())
    }
}
