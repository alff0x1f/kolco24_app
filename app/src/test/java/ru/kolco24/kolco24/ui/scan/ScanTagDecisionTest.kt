package ru.kolco24.kolco24.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.UnlockOutcome
import ru.kolco24.kolco24.data.db.CheckpointEntity

class ScanTagDecisionTest {

    private val code = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    private val uid = "04A2B3C4D5E680"

    private fun cp(id: Int, number: Int, cost: Int?) = CheckpointEntity(
        id = id,
        raceId = 1,
        number = number,
        cost = cost,
        type = "kp",
        description = if (cost == null) null else "desc",
        locked = cost == null,
    )

    private val checkpoints = mapOf(
        42 to cp(42, number = 7, cost = 50),
        99 to cp(99, number = 12, cost = null), // legend not synced for this checkpoint
    )

    @Test
    fun code_revealed_resolvesNumberAndCost() {
        val event = classifyTag(
            code = code,
            uid = uid,
            unlock = UnlockOutcome.Revealed(checkpointId = 42, checkpointIds = listOf(42)),
            bindings = emptyMap(),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.Kp(checkpointId = 42, number = 7, cost = 50, cpUid = uid, cpCode = "DEADBEEF"), event)
    }

    @Test
    fun code_identityOnly_resolvesNumberAndCost() {
        val event = classifyTag(
            code = code,
            uid = uid,
            unlock = UnlockOutcome.IdentityOnly(checkpointId = 42),
            bindings = emptyMap(),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.Kp(checkpointId = 42, number = 7, cost = 50, cpUid = uid, cpCode = "DEADBEEF"), event)
    }

    @Test
    fun code_unknown_badKp() {
        val event = classifyTag(code, uid, UnlockOutcome.Unknown, emptyMap(), checkpoints)
        assertTrue(event is ScanEvent.BadKp)
    }

    @Test
    fun code_failed_badKpCarriesReason() {
        val event = classifyTag(code, uid, UnlockOutcome.Failed("tamper"), emptyMap(), checkpoints)
        assertEquals(ScanEvent.BadKp("tamper"), event)
    }

    @Test
    fun code_costNull_badKp() {
        val event = classifyTag(
            code = code,
            uid = uid,
            unlock = UnlockOutcome.Revealed(checkpointId = 99, checkpointIds = listOf(99)),
            bindings = emptyMap(),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.BadKp("легенда не загружена"), event)
    }

    @Test
    fun code_checkpointMissingFromLegend_badKp() {
        val event = classifyTag(
            code = code,
            uid = uid,
            unlock = UnlockOutcome.Revealed(checkpointId = 777, checkpointIds = listOf(777)),
            bindings = emptyMap(),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.BadKp("легенда не загружена"), event)
    }

    @Test
    fun uid_inBindings_member() {
        val event = classifyTag(
            code = null,
            uid = uid,
            unlock = null,
            bindings = mapOf(uid to 3),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.Member(3), event)
    }

    @Test
    fun uid_notInBindings_unboundChip() {
        val event = classifyTag(
            code = null,
            uid = uid,
            unlock = null,
            bindings = mapOf("OTHER" to 3),
            checkpointsById = checkpoints,
        )
        assertEquals(ScanEvent.UnboundChip, event)
    }

    @Test
    fun code_withNullUnlock_badKp() {
        val event = classifyTag(code, uid, unlock = null, emptyMap(), checkpoints)
        assertEquals(ScanEvent.BadKp("не удалось расшифровать"), event)
    }
}
