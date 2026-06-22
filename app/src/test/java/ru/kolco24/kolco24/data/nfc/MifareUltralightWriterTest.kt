package ru.kolco24.kolco24.data.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MifareUltralightWriterTest {

    @Test
    fun newChipCode_alwaysReturns16Bytes() {
        assertEquals(CHIP_CODE_BYTES, newChipCode().size)
    }

    @Test
    fun newChipCode_twoCalls_produceDifferentCodes() {
        val a = newChipCode()
        val b = newChipCode()
        // Astronomically unlikely to collide; if it does, the RNG is broken.
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun chipCodeHex_knownVector_zeroAndFF() {
        assertEquals("00FF", chipCodeHex(byteArrayOf(0x00, 0xFF.toByte())))
    }

    @Test
    fun chipCodeHex_signedBytesHandled() {
        assertEquals("80817F00", chipCodeHex(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x7F, 0x00)))
    }

    @Test
    fun chipCodeHex_outputIsUppercase() {
        val hex = chipCodeHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()))
        assertEquals("ABCDEF", hex)
    }

    @Test
    fun chipCodeFromHex_knownVector() {
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte()), chipCodeFromHex("00FF"))
    }

    @Test
    fun chipCodeFromHex_roundTripWithChipCodeHex() {
        val original = byteArrayOf(0x04, 0xA2.toByte(), 0xB3.toByte(), 0xC4.toByte(),
            0xD5.toByte(), 0xE6.toByte(), 0x80.toByte(), 0x01,
            0x7F, 0x00, 0xFF.toByte(), 0x55, 0xAA.toByte(), 0x12, 0x34, 0x56)
        assertArrayEquals(original, chipCodeFromHex(chipCodeHex(original)))
    }

    @Test
    fun chipCodeHex_thenFromHex_roundTripForFullRandomCode() {
        val code = newChipCode()
        assertArrayEquals(code, chipCodeFromHex(chipCodeHex(code)))
    }

    // --- Raw chip record format ---------------------------------------------

    private val sampleCode = byteArrayOf(
        0x04, 0xA2.toByte(), 0xB3.toByte(), 0xC4.toByte(),
        0xD5.toByte(), 0xE6.toByte(), 0x80.toByte(), 0x01,
        0x7F, 0x00, 0xFF.toByte(), 0x55, 0xAA.toByte(), 0x12, 0x34, 0x56,
    )

    @Test
    fun buildChipRecord_length_is20() {
        assertEquals(20, buildChipRecord(CHIP_TYPE_KP, sampleCode).size)
    }

    @Test
    fun buildChipRecord_byteVector_magicPackedCode() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val expected = byteArrayOf(0x4B, 0x32, 0x34, 0x11) + sampleCode
        assertArrayEquals(expected, record)
    }

    @Test
    fun buildChipRecord_packedByte_isVersion1TypeKp() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        assertEquals(0x11.toByte(), record[3])
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildChipRecord_wrongSizeCode_throws() {
        buildChipRecord(CHIP_TYPE_KP, byteArrayOf(0x00, 0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildChipRecord_typeOutOfNibbleRange_throws() {
        buildChipRecord(16, sampleCode)
    }

    @Test
    fun parseChipRecord_validKp_returnsCode() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        assertArrayEquals(sampleCode, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_wrongMagicFirstByte_returnsNull() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        record[0] = 0x00
        assertEquals(null, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_wrongMagicSecondByte_returnsNull() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        record[1] = 0x00
        assertEquals(null, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_wrongMagicThirdByte_returnsNull() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        record[2] = 0x35
        assertEquals(null, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_allZero_returnsNull() {
        assertEquals(null, parseChipRecord(ByteArray(20)))
    }

    @Test
    fun parseChipRecord_tooShort_returnsNull() {
        assertEquals(null, parseChipRecord(ByteArray(19)))
    }

    @Test
    fun parseChipRecord_participantType_returnsNull() {
        // version 1, type 2 (participant) → packed 0x12
        val record = byteArrayOf(0x4B, 0x32, 0x34, 0x12) + sampleCode
        assertEquals(null, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_unknownVersion_returnsNull() {
        // version 2, type 1 (КП) → packed 0x21
        val record = byteArrayOf(0x4B, 0x32, 0x34, 0x21) + sampleCode
        assertEquals(null, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_trailingPadding_tolerated() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode) + ByteArray(8)
        assertArrayEquals(sampleCode, parseChipRecord(record))
    }

    @Test
    fun parseChipRecord_roundTripWithBuildChipRecord() {
        val code = newChipCode()
        assertArrayEquals(code, parseChipRecord(buildChipRecord(CHIP_TYPE_KP, code)))
    }

    // --- GET_VERSION model parsing ------------------------------------------

    @Test
    fun chipModelFromVersion_ntag213Vector() {
        val resp = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)
        assertEquals("NTAG213", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_ntag215Vector() {
        val resp = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x11, 0x03)
        assertEquals("NTAG215", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_ntag216Vector() {
        val resp = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x13, 0x03)
        assertEquals("NTAG216", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_ultralightProductByte() {
        val resp = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03)
        assertEquals("MIFARE Ultralight", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_unknownNtagStorage() {
        val resp = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x7F, 0x03)
        assertEquals("NTAG (неизвестно)", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_unknownProductType() {
        val resp = byteArrayOf(0x00, 0x04, 0x99.toByte(), 0x02, 0x01, 0x00, 0x0F, 0x03)
        assertEquals("неизвестно", chipModelFromVersion(resp))
    }

    @Test
    fun chipModelFromVersion_emptyResponse_returnsUnknown() {
        assertEquals("неизвестно", chipModelFromVersion(ByteArray(0)))
    }

    @Test
    fun chipModelFromVersion_shortResponse_returnsUnknown() {
        assertEquals("неизвестно", chipModelFromVersion(byteArrayOf(0x00, 0x04, 0x04)))
    }

    // --- Transport-driven write/read sequencing (fake NfcTransport) ----------

    /** Records every frame sent and answers via [responder] (which may throw to simulate I/O). */
    private class FakeTransport(val responder: (ByteArray) -> ByteArray) : NfcTransport {
        val frames = mutableListOf<ByteArray>()
        override fun transceive(frame: ByteArray): ByteArray {
            frames.add(frame.copyOf())
            return responder(frame)
        }
    }

    private val WRITE = 0xA2.toByte()
    private val ACK = byteArrayOf(0x0A)
    private val NAK = byteArrayOf(0x00)
    private val FAST_READ = 0x3A.toByte()
    private val READ = 0x30.toByte()

    @Test
    fun writeRecord_writesHeaderLast_andSucceeds() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when (frame[0]) {
                WRITE -> ACK
                FAST_READ -> record // read-back sees the valid record
                else -> NAK
            }
        }
        assertEquals(ChipWriteResult.Success, writeRecord(t, record))

        val writes = t.frames.filter { it[0] == WRITE }
        assertEquals(6, writes.size) // page-4 invalidate + 4 code pages + page-4 header
        // first write invalidates page 4 with an all-zero header
        assertEquals(4.toByte(), writes[0][1])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), writes[0].copyOfRange(2, 6))
        // code pages 5..8 are written in between, with correct payload bytes
        assertEquals(listOf(5, 6, 7, 8), writes.subList(1, 5).map { it[1].toInt() })
        for (i in 0..3) {
            assertArrayEquals(
                "code page ${5 + i} payload",
                record.copyOfRange(4 + i * 4, 8 + i * 4),
                writes[1 + i].copyOfRange(2, 6),
            )
        }
        // the valid header is committed to page 4 last
        val last = writes.last()
        assertEquals(4.toByte(), last[1])
        assertArrayEquals(byteArrayOf(0x4B, 0x32, 0x34, 0x11), last.copyOfRange(2, 6))
    }

    @Test
    fun writeRecord_nakOnWrite_failsWithNoFurtherFrames() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { NAK } // NAK every frame
        assertTrue(writeRecord(t, record) is ChipWriteResult.Failed)
        assertEquals(1, t.frames.size) // stopped after the first (invalidate) WRITE
    }

    @Test
    fun writeRecord_nakOnCodePage_returnsFailed() {
        // Invalidate (page 4) ACKs, but the first code page (page 5) NAKs → Failed, stops early.
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        var writeCount = 0
        val t = FakeTransport { frame ->
            if (frame[0] == WRITE) {
                writeCount++
                if (writeCount == 1) ACK else NAK // ACK invalidate, NAK code page 5
            } else NAK
        }
        assertTrue(writeRecord(t, record) is ChipWriteResult.Failed)
        // Only 2 WRITEs: the invalidate + the first code page (which NAKed).
        val writes = t.frames.filter { it[0] == WRITE }
        assertEquals(2, writes.size)
    }

    @Test
    fun writeRecord_ioExceptionOnWrite_returnsFailed() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            if (frame[0] == WRITE) throw IOException("connection lost") else NAK
        }
        assertTrue(writeRecord(t, record) is ChipWriteResult.Failed)
    }

    @Test
    fun writeRecord_readBackMismatch_returnsFailed() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when (frame[0]) {
                WRITE -> ACK
                FAST_READ -> ByteArray(20) // all-zero → parses null → mismatch
                else -> NAK
            }
        }
        assertTrue(writeRecord(t, record) is ChipWriteResult.Failed)
    }

    @Test
    fun readRecord_fastReadHappyPath_returnsCode() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame -> if (frame[0] == FAST_READ) record else NAK }
        assertArrayEquals(sampleCode, readRecord(t))
    }

    /** Page-4 READ → record bytes 0..15; page-8 READ → bytes 16..19 (+ padding). */
    private fun fallbackRead(record: ByteArray, frame: ByteArray): ByteArray = when (frame[1]) {
        4.toByte() -> record.copyOfRange(0, 16)
        8.toByte() -> record.copyOfRange(16, 20) + ByteArray(12)
        else -> NAK
    }

    @Test
    fun readRecord_fastReadThrows_fallsBackToReads() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when (frame[0]) {
                FAST_READ -> throw IOException("no fast_read")
                READ -> fallbackRead(record, frame)
                else -> NAK
            }
        }
        assertArrayEquals(sampleCode, readRecord(t))
    }

    @Test
    fun readRecord_fastReadNak_fallsBackToReads() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when (frame[0]) {
                FAST_READ -> NAK // 1-byte NAK, shorter than 20 bytes
                READ -> fallbackRead(record, frame)
                else -> NAK
            }
        }
        assertArrayEquals(sampleCode, readRecord(t))
    }

    @Test
    fun readRecord_shortSecondRead_returnsNull() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when {
                frame[0] == FAST_READ -> NAK
                frame[0] == READ && frame[1] == 4.toByte() -> record.copyOfRange(0, 16)
                frame[0] == READ && frame[1] == 8.toByte() -> NAK // short second READ
                else -> NAK
            }
        }
        assertEquals(null, readRecord(t))
    }

    @Test
    fun readRecord_ioExceptionOnFirstRead_returnsNull() {
        val t = FakeTransport { frame ->
            when {
                frame[0] == FAST_READ -> NAK
                frame[0] == READ && frame[1] == 4.toByte() -> throw IOException("read fail")
                else -> NAK
            }
        }
        assertEquals(null, readRecord(t))
    }

    @Test
    fun readRecord_ioExceptionOnSecondRead_returnsNull() {
        val record = buildChipRecord(CHIP_TYPE_KP, sampleCode)
        val t = FakeTransport { frame ->
            when {
                frame[0] == FAST_READ -> NAK
                frame[0] == READ && frame[1] == 4.toByte() -> record.copyOfRange(0, 16)
                frame[0] == READ && frame[1] == 8.toByte() -> throw IOException("read fail")
                else -> NAK
            }
        }
        assertEquals(null, readRecord(t))
    }
}
