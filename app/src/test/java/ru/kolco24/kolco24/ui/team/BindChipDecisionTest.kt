package ru.kolco24.kolco24.ui.team

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.MemberChipBindingEntity

class BindChipDecisionTest {

    private val slot = SlotKey(teamId = 7, numberInTeam = 2)
    private val uid = "04A2B3C4D5E680"

    @Test
    fun uidNotInPool_notInPool() {
        val outcome = decideBind(uid, poolNumber = null, existing = null, currentSlot = slot)
        assertEquals(BindOutcome.NotInPool, outcome)
    }

    @Test
    fun uidNotInPool_takesPrecedenceOverExistingBinding() {
        // A uid that fell out of the pool must refuse even if some stale binding holds it.
        val existing = MemberChipBindingEntity(teamId = 9, numberInTeam = 1, nfcUid = uid, participantNumber = 101)
        val outcome = decideBind(uid, poolNumber = null, existing = existing, currentSlot = slot)
        assertEquals(BindOutcome.NotInPool, outcome)
    }

    @Test
    fun uidInPool_unbound_readyToBind() {
        val outcome = decideBind(uid, poolNumber = 101, existing = null, currentSlot = slot)
        assertEquals(BindOutcome.ReadyToBind(101), outcome)
    }

    @Test
    fun uidBoundToAnotherSlot_alreadyBound() {
        val existing = MemberChipBindingEntity(teamId = 7, numberInTeam = 5, nfcUid = uid, participantNumber = 101)
        val outcome = decideBind(uid, poolNumber = 101, existing = existing, currentSlot = slot)
        assertEquals(BindOutcome.AlreadyBound(SlotKey(7, 5), 101), outcome)
    }

    @Test
    fun uidBoundOnAnotherTeamSameNumberInTeam_alreadyBound() {
        // Same numberInTeam but a different team is still a different slot.
        val existing = MemberChipBindingEntity(teamId = 8, numberInTeam = 2, nfcUid = uid, participantNumber = 101)
        val outcome = decideBind(uid, poolNumber = 101, existing = existing, currentSlot = slot)
        assertEquals(BindOutcome.AlreadyBound(SlotKey(8, 2), 101), outcome)
    }

    @Test
    fun uidAlreadyOnThisSlot_alreadyOnThisSlot() {
        val existing = MemberChipBindingEntity(teamId = 7, numberInTeam = 2, nfcUid = uid, participantNumber = 101)
        val outcome = decideBind(uid, poolNumber = 101, existing = existing, currentSlot = slot)
        assertEquals(BindOutcome.AlreadyOnThisSlot, outcome)
    }
}
