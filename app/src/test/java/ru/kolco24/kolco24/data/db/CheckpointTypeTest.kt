package ru.kolco24.kolco24.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckpointTypeTest {

    @Test
    fun `normalizeCpType trims and lowercases`() {
        assertEquals(CP_TYPE_FINISH, normalizeCpType(" Finish "))
        assertEquals(CP_TYPE_START, normalizeCpType("START"))
        assertEquals(CP_TYPE_TEST, normalizeCpType("\ttest"))
        assertEquals(CP_TYPE_KP, normalizeCpType("kp"))
        assertEquals("", normalizeCpType("  "))
    }

    @Test
    fun `normalizeCpType keeps null`() {
        assertNull(normalizeCpType(null))
    }
}
