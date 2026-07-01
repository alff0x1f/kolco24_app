package ru.kolco24.kolco24.data.lease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceLeaseStoreTest {

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
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun write_thenRead_roundTrips() {
        val store = FakeStore()
        val s = RaceLeaseStore(store.load, store.save)
        val lease = RaceLease(raceId = 42, expiresAtMs = 1_700_000_000_000L)

        s.write(lease)

        assertEquals(lease, s.read())
    }

    @Test
    fun write_storesSingleKey() {
        val store = FakeStore()
        val s = RaceLeaseStore(store.load, store.save)
        s.write(RaceLease(raceId = 1, expiresAtMs = 2L))

        assertEquals(setOf("lease"), store.map.keys)
    }

    @Test
    fun clear_removesKey() {
        val store = FakeStore()
        val s = RaceLeaseStore(store.load, store.save)
        s.write(RaceLease(raceId = 1, expiresAtMs = 2L))

        s.clear()

        assertNull(s.read())
        assertNull(store.map["lease"])
    }

    @Test
    fun read_reflectsPreSeededStore() {
        val store = FakeStore(mapOf("lease" to "42|1700000000000"))
        assertEquals(RaceLease(42, 1_700_000_000_000L), RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenTooFewFields() {
        val store = FakeStore(mapOf("lease" to "42"))
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenTooManyFields() {
        val store = FakeStore(mapOf("lease" to "42|1700000000000|extra"))
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenNonNumericRaceId() {
        val store = FakeStore(mapOf("lease" to "abc|1700000000000"))
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenNonNumericExpiresAt() {
        val store = FakeStore(mapOf("lease" to "42|xyz"))
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }

    @Test
    fun read_returnsNull_whenEmptyString() {
        val store = FakeStore(mapOf("lease" to ""))
        assertNull(RaceLeaseStore(store.load, store.save).read())
    }
}
