package ru.kolco24.kolco24.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapUrlTest {

    private val base = "https://kolco24.ru"

    @Test
    fun nullAndBlankAreNoMap() {
        assertNull(resolveMapUrl(null, base))
        assertNull(resolveMapUrl("", base))
        assertNull(resolveMapUrl("  ", base))
    }

    @Test
    fun absoluteUrlIsKeptAsIs() {
        assertEquals("https://cdn.example.com/r8", resolveMapUrl("https://cdn.example.com/r8", base))
    }

    @Test
    fun rootRelativePathResolvesAgainstBaseHost() {
        assertEquals("https://kolco24.ru/media/maps/8.mbtiles", resolveMapUrl("/media/maps/8.mbtiles", base))
        assertEquals("https://kolco24.ru/media/maps/8.mbtiles", resolveMapUrl("/media/maps/8.mbtiles", "$base/"))
    }

    @Test
    fun rootRelativePathDropsBasePath() {
        assertEquals(
            "https://kolco24.ru/media/maps/8.mbtiles",
            resolveMapUrl("/media/maps/8.mbtiles", "https://kolco24.ru/api/"),
        )
    }

    @Test
    fun lanBaseKeepsSchemeAndPort() {
        assertEquals(
            "http://192.168.1.10:8000/media/maps/8.mbtiles",
            resolveMapUrl("/media/maps/8.mbtiles", "http://192.168.1.10:8000"),
        )
    }

    @Test
    fun pathsThatCouldEscapeTheHostAreRejected() {
        assertNull(resolveMapUrl("//evil.com/8.mbtiles", base))
        assertNull(resolveMapUrl("/\\evil.com/8.mbtiles", base))
        assertNull(resolveMapUrl("/\t/evil.com/8.mbtiles", base))
        assertNull(resolveMapUrl("/\n/evil.com/8.mbtiles", base))
        assertNull(resolveMapUrl("/media/maps/a b.mbtiles", base))
    }

    @Test
    fun invalidBaseIsNoMap() {
        assertNull(resolveMapUrl("/media/maps/8.mbtiles", "not a url"))
    }
}
