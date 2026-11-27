package com.ali.menbaradkshk.util

import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupReadTest {
    @Test fun exactBytesFitButStopsAfterSingleBoundaryByte() = runBlocking {
        assertEquals("أب", readBoundedBackup("أب".byteInputStream(), limit = 4))
        var read = 0
        val oversized = object : InputStream() {
            override fun read(): Int { read += 1; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len)
                read += len
                return len
            }
        }
        assertTrue(runCatching { readBoundedBackup(oversized, limit = 32) }.isFailure)
        assertEquals(33, read)
    }
}
