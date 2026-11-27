package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AdminMergedAudioReviewTest {
    @Test fun onlySelfContainedSingleTrackAacLcM4aIsAccepted() {
        val fixture = m4a()
        assertEquals(ReviewedAudioContainer.M4aLc, readAudioContainer(fixture))
        assertNull(readAudioContainer(m4a(trackKind = "vide")))
        assertNull(readAudioContainer(m4a(twoTracks = true)))
        assertNull(readAudioContainer(m4a(objectType = 5)))
        assertNull(readAudioContainer(m4a(externalData = true)))
        assertNull(readAudioContainer(fixture.copyOf(fixture.size - 1)))
    }

    @Test fun mp3NeedsContiguousLayerThreeFramesAndMatchingTransportShape() {
        val frame = ByteArray(192).apply {
            this[0] = 0xff.toByte()
            this[1] = 0xfb.toByte()
            this[2] = 0x54.toByte() // MPEG1 64kbps, 48kHz.
        }
        assertNull(readAudioContainer(frame))
        assertEquals(ReviewedAudioContainer.Mp3, readAudioContainer(frame + frame))
        assertNull(readAudioContainer(frame + frame + byteArrayOf(1)))
        assertNull(readAudioContainer(frame + frame.copyOf().apply { this[3] = 0xc0.toByte() }))
        assertNull(readAudioContainer("ID3".encodeToByteArray() + ByteArray(20)))
    }

    @Test fun realEncoderFixturesOnOfflineVerification() {
        val path = System.getProperty("minbar.mergeAudioFixtures") ?: return
        val dir = File(path)
        val audio = dir.resolve("aac.m4a").readBytes()
        val mp3 = dir.resolve("merged.mp3").readBytes()
        val video = dir.resolve("video.mp4").readBytes()
        assertEquals(ReviewedAudioContainer.M4aLc, readAudioContainer(audio))
        assertEquals(ReviewedAudioContainer.Mp3, readAudioContainer(mp3))
        assertNull(readAudioContainer(video))
        assertNull(readAudioContainer(mp3.copyOf(mp3.size - 1)))
    }

    private fun b(i: Int) = ByteBuffer.allocate(4).putInt(i).array()
    private fun s(i: Int) = ByteBuffer.allocate(2).putShort(i.toShort()).array()
    private fun box(name: String, payload: ByteArray): ByteArray =
        b(payload.size + 8) + name.encodeToByteArray() + payload
    private fun desc(tag: Int, data: ByteArray) = byteArrayOf(tag.toByte(), data.size.toByte()) + data
    private fun m4a(trackKind: String = "soun", objectType: Int = 2, twoTracks: Boolean = false,
                    externalData: Boolean = false): ByteArray {
        val asc = desc(5, byteArrayOf((objectType shl 3 or 1).toByte(), 0x90.toByte()))
        val decoder = desc(4, byteArrayOf(0x40, 0x15) + ByteArray(11) + asc)
        val record = desc(3, ByteArray(3) + decoder + desc(6, byteArrayOf(2)))
        val mp4a = box("mp4a", ByteArray(6) + s(1) + ByteArray(8) + s(2) + ByteArray(10) +
            box("esds", b(0) + record))
        val stbl = box("stbl", box("stsd", b(0) + b(1) + mp4a))
        val reference = box("url ", b(if (externalData) 0 else 1))
        val dinf = box("dinf", box("dref", b(0) + b(1) + reference))
        val minf = box("minf", dinf + stbl)
        val mdia = box("mdia", box("hdlr", ByteArray(8) + trackKind.encodeToByteArray()) + minf)
        val trak = box("trak", mdia)
        return box("ftyp", "M4A ".encodeToByteArray()+b(0)) + box("mdat", byteArrayOf(0x10)) +
            box("moov", if (twoTracks) trak + trak else trak)
    }
}
