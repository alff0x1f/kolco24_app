package ru.kolco24.kolco24.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockAnchorStoreTest {

    /** In-memory fake of the injected single-key store; `null` save removes the key. */
    private class FakeStore(seed: Map<String, String> = emptyMap()) {
        val map = seed.toMutableMap()
        val load: (String) -> String? = { map[it] }
        val save: (String, String?) -> Unit = { key, value ->
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun read_returnsNull_whenStoreEmpty() {
        val store = FakeStore()
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }

    @Test
    fun write_thenRead_roundTrips_withBootCount() {
        val store = FakeStore()
        val s = ClockAnchorStore(store.load, store.save)
        val anchor = ClockAnchor(
            serverEpochMs = 1_700_000_000_000L,
            anchorElapsedMs = 123_456L,
            capturedWallMs = 1_700_000_000_500L,
            bootCount = 7,
        )

        s.write(anchor)

        assertEquals(anchor, s.read())
    }

    @Test
    fun write_thenRead_roundTrips_withNullBootCount() {
        val store = FakeStore()
        val s = ClockAnchorStore(store.load, store.save)
        val anchor = ClockAnchor(
            serverEpochMs = 1_700_000_000_000L,
            anchorElapsedMs = 123_456L,
            capturedWallMs = 1_700_000_000_500L,
            bootCount = null,
        )

        s.write(anchor)

        assertEquals(anchor, s.read())
    }

    @Test
    fun write_storesSingleKey() {
        // Atomic-write invariant (P1): exactly one key persisted, not four.
        val store = FakeStore()
        val s = ClockAnchorStore(store.load, store.save)
        s.write(ClockAnchor(1L, 2L, 3L, 4))

        assertEquals(setOf("anchor"), store.map.keys)
    }

    @Test
    fun clear_removesKey() {
        val store = FakeStore()
        val s = ClockAnchorStore(store.load, store.save)
        s.write(ClockAnchor(1L, 2L, 3L, 4))

        s.clear()

        assertNull(s.read())
        assertNull(store.map["anchor"])
    }

    @Test
    fun read_reflectsPreSeededStore() {
        val store = FakeStore(mapOf("anchor" to "1700000000000|123456|1700000000500|7"))
        assertEquals(
            ClockAnchor(1_700_000_000_000L, 123_456L, 1_700_000_000_500L, 7),
            ClockAnchorStore(store.load, store.save).read(),
        )
    }

    @Test
    fun read_preSeeded_emptyBootSegment_isNullBootCount() {
        val store = FakeStore(mapOf("anchor" to "1700000000000|123456|1700000000500|"))
        assertEquals(
            ClockAnchor(1_700_000_000_000L, 123_456L, 1_700_000_000_500L, null),
            ClockAnchorStore(store.load, store.save).read(),
        )
    }

    @Test
    fun read_returnsNull_whenTooFewFields() {
        val store = FakeStore(mapOf("anchor" to "1700000000000|123456|1700000000500"))
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenTooManyFields() {
        val store = FakeStore(mapOf("anchor" to "1|2|3|4|5"))
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenNonNumericLongSegment() {
        val store = FakeStore(mapOf("anchor" to "abc|123456|1700000000500|7"))
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenNonNumericBootSegment() {
        val store = FakeStore(mapOf("anchor" to "1700000000000|123456|1700000000500|xx"))
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenEmptyString() {
        val store = FakeStore(mapOf("anchor" to ""))
        assertNull(ClockAnchorStore(store.load, store.save).read())
    }
}
