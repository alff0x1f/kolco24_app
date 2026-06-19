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
}
