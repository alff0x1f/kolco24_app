package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NfcUidTest {

    @Test
    fun singleLeadingZeroByte_zeroPaddedUppercaseHex() {
        assertEquals("04", normalizeNfcUid(byteArrayOf(0x04)))
    }

    @Test
    fun multiByteUid_concatenatedUppercaseHex() {
        val bytes = byteArrayOf(0x04, 0xA2.toByte(), 0xB3.toByte(), 0xC4.toByte(), 0xD5.toByte(), 0xE6.toByte(), 0x80.toByte())
        assertEquals("04A2B3C4D5E680", normalizeNfcUid(bytes))
    }

    @Test
    fun fullByteRange_handlesSignedBytes() {
        assertEquals("00FF7F80", normalizeNfcUid(byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte())))
    }

    @Test
    fun emptyArray_emptyString() {
        assertEquals("", normalizeNfcUid(byteArrayOf()))
    }
}
