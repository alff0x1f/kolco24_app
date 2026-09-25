package ru.kolco24.kolco24.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyedValueTest {

    @Test
    fun `value for key only passes a matching tag`() {
        assertEquals(5, valueForKey(7 to 5, 7))
        assertNull(valueForKey(6 to 5, 7))
        assertNull(valueForKey(7 to 5, null))
        assertNull(valueForKey<Int, Int>(null, 7))
    }
}
