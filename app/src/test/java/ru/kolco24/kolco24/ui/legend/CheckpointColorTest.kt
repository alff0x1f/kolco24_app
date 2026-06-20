package ru.kolco24.kolco24.ui.legend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckpointColorTest {

    @Test
    fun knownTokens_mapToEnum() {
        assertEquals(CheckpointColor.RED, parseCheckpointColor("red"))
        assertEquals(CheckpointColor.BLUE, parseCheckpointColor("blue"))
        assertEquals(CheckpointColor.GREEN, parseCheckpointColor("green"))
        assertEquals(CheckpointColor.YELLOW, parseCheckpointColor("yellow"))
        assertEquals(CheckpointColor.ORANGE, parseCheckpointColor("orange"))
        assertEquals(CheckpointColor.PURPLE, parseCheckpointColor("purple"))
    }

    @Test
    fun emptyToken_isNull() {
        assertNull(parseCheckpointColor(""))
        assertNull(parseCheckpointColor("   "))
    }

    @Test
    fun unknownToken_isNull() {
        assertNull(parseCheckpointColor("teal"))
        assertNull(parseCheckpointColor("#FF0000"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(CheckpointColor.RED, parseCheckpointColor("RED"))
        assertEquals(CheckpointColor.RED, parseCheckpointColor("Red"))
    }

    @Test
    fun whitespaceTolerant() {
        assertEquals(CheckpointColor.RED, parseCheckpointColor(" red "))
        assertEquals(CheckpointColor.BLUE, parseCheckpointColor("\tblue\n"))
    }
}
