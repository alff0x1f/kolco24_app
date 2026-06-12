package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallIdTest {

    /** Minimal in-memory key-value store standing in for SharedPreferences. */
    private class FakeStore {
        var value: String? = null
        var saveCount = 0
        fun load(): String? = value
        fun save(v: String) {
            value = v
            saveCount++
        }
    }

    @Test
    fun `generates and persists on first call`() {
        val store = FakeStore()

        val id = InstallId.getOrCreate(store::load, store::save)

        assertEquals(id, store.value)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `returns the same value on repeated calls without re-saving`() {
        val store = FakeStore()

        val first = InstallId.getOrCreate(store::load, store::save)
        val second = InstallId.getOrCreate(store::load, store::save)

        assertEquals(first, second)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `generated id is at most 64 chars (API limit)`() {
        val store = FakeStore()

        val id = InstallId.getOrCreate(store::load, store::save)

        assertTrue("install id length ${id.length} exceeds 64", id.length <= 64)
    }
}
