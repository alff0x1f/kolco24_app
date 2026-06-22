package ru.kolco24.kolco24.data.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
