package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.CHECK_METHOD_OFFLINE
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.track.UploadTarget

class CheckMethodTest {

    private fun mark(complete: Boolean, method: String, confirmedAt: Long?) = MarkEntity(
        id = "m",
        raceId = 1,
        teamId = 7,
        checkpointId = 10,
        checkpointNumber = 10,
        cost = 5,
        method = "nfc",
        cpUid = "U",
        cpCode = "C",
        present = listOf(1),
        expectedCount = 1,
        complete = complete,
        takenAt = 1_000L,
        updatedAt = 1_000L,
        checkMethod = method,
        confirmedAt = confirmedAt,
    )

    @Test
    fun offlineWire_matchesColumnDefault() {
        // The DB column default (schemas/8.json, MIGRATION_7_8) must be the Offline wire string.
        assertEquals("offline", CHECK_METHOD_OFFLINE)
        assertEquals(CheckMethod.Offline.wire, CHECK_METHOD_OFFLINE)
    }

    @Test
    fun parse_knownValues() {
        assertEquals(CheckMethod.Offline, CheckMethod.parse("offline"))
        assertEquals(CheckMethod.Cloud, CheckMethod.parse("cloud"))
        assertEquals(CheckMethod.Local, CheckMethod.parse("local"))
    }

    @Test
    fun parse_unknownOrMissing_isOffline() {
        assertEquals(CheckMethod.Offline, CheckMethod.parse("online"))
        assertEquals(CheckMethod.Offline, CheckMethod.parse("local_server"))
        assertEquals(CheckMethod.Offline, CheckMethod.parse(""))
        assertEquals(CheckMethod.Offline, CheckMethod.parse(null))
        // Case-sensitive: the server sends lowercase.
        assertEquals(CheckMethod.Offline, CheckMethod.parse("Cloud"))
    }

    @Test
    fun uploadTarget_mapping() {
        assertEquals(UploadTarget.Cloud, CheckMethod.Cloud.uploadTarget)
        assertEquals(UploadTarget.Local, CheckMethod.Local.uploadTarget)
        assertNull(CheckMethod.Offline.uploadTarget)
    }

    @Test
    fun isCountedAndIsUnconfirmed_matrix() {
        data class Case(val complete: Boolean, val method: String, val confirmedAt: Long?, val counted: Boolean, val unconfirmed: Boolean)
        val cases = listOf(
            // offline: complete counts regardless of confirmedAt
            Case(true, "offline", null, counted = true, unconfirmed = false),
            Case(true, "offline", 5L, counted = true, unconfirmed = false),
            Case(false, "offline", null, counted = false, unconfirmed = false),
            // unknown method behaves as offline
            Case(true, "weird", null, counted = true, unconfirmed = false),
            // cloud / local: need confirmation
            Case(true, "cloud", null, counted = false, unconfirmed = true),
            Case(true, "cloud", 5L, counted = true, unconfirmed = false),
            Case(false, "cloud", null, counted = false, unconfirmed = false),
            Case(false, "cloud", 5L, counted = false, unconfirmed = false),
            Case(true, "local", null, counted = false, unconfirmed = true),
            Case(true, "local", 5L, counted = true, unconfirmed = false),
            Case(false, "local", null, counted = false, unconfirmed = false),
        )
        for (c in cases) {
            val m = mark(c.complete, c.method, c.confirmedAt)
            assertEquals("isCounted $c", c.counted, m.isCounted())
            assertEquals("isUnconfirmed $c", c.unconfirmed, m.isUnconfirmed())
        }
    }

    @Test
    fun defaultEntity_isOfflineAndCounted() {
        val m = MarkEntity(
            id = "d", raceId = 1, teamId = 7, checkpointId = 1, checkpointNumber = 1, cost = 1,
            method = "nfc", cpUid = "", cpCode = "", present = listOf(1), expectedCount = 1,
            complete = true, takenAt = 0L, updatedAt = 0L,
        )
        assertTrue(m.isCounted())
        assertFalse(m.isUnconfirmed())
    }
}
