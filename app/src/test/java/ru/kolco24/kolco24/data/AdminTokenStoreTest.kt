package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminTokenStoreTest {

    /** In-memory fake of the injected multi-key store; `null` save removes the key. */
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
        val s = AdminTokenStore(store.load, store.save)
        assertNull(s.read())
    }

    @Test
    fun write_thenRead_roundTrips() {
        val store = FakeStore()
        val s = AdminTokenStore(store.load, store.save)

        s.write("tok123", "admin@kolco24.ru", "2026-07-21T14:03:00Z")

        val read = s.read()
        assertEquals(StoredSession("tok123", "admin@kolco24.ru", "2026-07-21T14:03:00Z"), read)
    }

    @Test
    fun read_reflectsPreSeededStore() {
        val store = FakeStore(
            mapOf(
                "admin_token" to "seeded",
                "admin_email" to "pre@seed.ru",
                "admin_token_expires_at" to "2026-08-01T00:00:00Z",
            )
        )
        val s = AdminTokenStore(store.load, store.save)
        assertEquals(StoredSession("seeded", "pre@seed.ru", "2026-08-01T00:00:00Z"), s.read())
    }

    @Test
    fun clear_removesAllThreeKeys() {
        val store = FakeStore()
        val s = AdminTokenStore(store.load, store.save)
        s.write("tok", "a@b.ru", "2026-07-21T14:03:00Z")

        s.clear()

        assertNull(s.read())
        assertNull(store.map["admin_token"])
        assertNull(store.map["admin_email"])
        assertNull(store.map["admin_token_expires_at"])
    }

    @Test
    fun read_returnsNull_whenAnySingleKeyMissing() {
        // Only token + email present, expiry absent → incomplete session is null.
        val store = FakeStore(
            mapOf(
                "admin_token" to "tok",
                "admin_email" to "a@b.ru",
            )
        )
        val s = AdminTokenStore(store.load, store.save)
        assertNull(s.read())
    }
}
