package ru.kolco24.kolco24.ui.marks

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.kolco24.kolco24.ui.legend.CheckpointColor

class TileFillTest {

    @Test
    fun `each color maps to its muted fill`() {
        assertEquals(Color(0xFFCB4233), tileFill(CheckpointColor.RED, false).fill)
        assertEquals(Color(0xFFC15A2E), tileFill(CheckpointColor.ORANGE, false).fill)
        assertEquals(Color(0xFF2F6CAE), tileFill(CheckpointColor.BLUE, false).fill)
        assertEquals(Color(0xFF2E9E57), tileFill(CheckpointColor.GREEN, false).fill)
        assertEquals(Color(0xFFC99A1E), tileFill(CheckpointColor.YELLOW, false).fill)
        assertEquals(Color(0xFF7C5AC0), tileFill(CheckpointColor.PURPLE, false).fill)
    }

    @Test
    fun `white text on red orange blue green purple`() {
        for (c in listOf(
            CheckpointColor.RED,
            CheckpointColor.ORANGE,
            CheckpointColor.BLUE,
            CheckpointColor.GREEN,
            CheckpointColor.PURPLE,
        )) {
            assertEquals("text on $c", Color.White, tileFill(c, false).text)
        }
    }

    @Test
    fun `yellow uses dark ink text`() {
        assertEquals(Color(0xFF161A1F), tileFill(CheckpointColor.YELLOW, false).text)
    }

    @Test
    fun `neutral fill differs light vs dark`() {
        val light = tileFill(null, false)
        val dark = tileFill(null, true)
        assertEquals(Color(0xFFD6DCE4), light.fill)
        assertEquals(Color(0xFF161A1F), light.text)
        assertEquals(Color(0xFF2A323C), dark.fill)
        assertEquals(Color(0xFFD6DCE4), dark.text)
        assertNotEquals(light.fill, dark.fill)
        assertNotEquals(light.text, dark.text)
    }

    @Test
    fun `non-neutral fills identical in light and dark`() {
        for (c in CheckpointColor.entries) {
            assertEquals("fill for $c", tileFill(c, false).fill, tileFill(c, true).fill)
            assertEquals("text for $c", tileFill(c, false).text, tileFill(c, true).text)
        }
    }
}
