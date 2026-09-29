package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.MemberTagEntity
import ru.kolco24.kolco24.data.nfc.ChipCodes
import ru.kolco24.kolco24.data.nfc.ChipWriteResult

class MemberProvisioningModelTest {

    private val code = ByteArray(16) { it.toByte() }
    private val codeHex = "000102030405060708090A0B0C0D0E0F"
    private val pending = MemberProvisionState.WaitingForWrite("AA", 7, codeHex, MEMBER_WRITE_AGAIN_HINT)

    // --- routeMemberTap ---

    @Test
    fun route_idleStates_classify() {
        assertEquals(MemberTapRoute.Classify, routeMemberTap(MemberProvisionState.WaitingForChip, "AA"))
        assertEquals(MemberTapRoute.Classify, routeMemberTap(MemberProvisionState.Success(3), "AA"))
        assertEquals(MemberTapRoute.Classify, routeMemberTap(MemberProvisionState.Failed("x"), "AA"))
    }

    @Test
    fun route_busyStates_ignore() {
        assertEquals(MemberTapRoute.Ignore, routeMemberTap(MemberProvisionState.Binding("AA", null), "BB"))
        assertEquals(MemberTapRoute.Ignore, routeMemberTap(MemberProvisionState.Writing("AA", 1), "AA"))
    }

    @Test
    fun route_needsNumber_sameUidIgnored_otherUidClassified() {
        val state = MemberProvisionState.NeedsNumber("AA")
        assertEquals(MemberTapRoute.Ignore, routeMemberTap(state, "AA"))
        assertEquals(MemberTapRoute.Classify, routeMemberTap(state, "BB"))
    }

    @Test
    fun route_waitingForWrite_sameUidWrites() {
        assertEquals(MemberTapRoute.Write(pending), routeMemberTap(pending, "AA"))
    }

    @Test
    fun route_waitingForWrite_otherUidHintsKeepingPending() {
        val route = routeMemberTap(pending, "BB")
        assertEquals(MemberTapRoute.Hint(pending.copy(hint = MEMBER_SAME_BRACELET_HINT)), route)
    }

    // --- classifyMemberTap ---

    @Test
    fun classify_readFailed_fails() {
        assertTrue(classifyMemberTap("AA", null, known = true) is MemberTapAction.Fail)
    }

    @Test
    fun classify_kpChip_refusedEvenWhenKnown() {
        val action = classifyMemberTap("AA", ChipCodes(code = code, memberCode = null), known = true)
        assertEquals(MemberTapAction.Fail("Это чип КП, а не браслет"), action)
    }

    @Test
    fun classify_known_bindsWithoutNumber() {
        assertEquals(MemberTapAction.BindKnown("AA"), classifyMemberTap("AA", ChipCodes(null, null), known = true))
    }

    @Test
    fun classify_knownWithMemberCode_rebinds() {
        assertEquals(MemberTapAction.BindKnown("AA"), classifyMemberTap("AA", ChipCodes(null, code), known = true))
    }

    @Test
    fun classify_unknown_asksNumber() {
        assertEquals(MemberTapAction.AskNumber("AA"), classifyMemberTap("AA", ChipCodes(null, null), known = false))
    }

    // --- isKnownBracelet ---

    @Test
    fun known_fromPoolOrFeed() {
        val pool = listOf(MemberTagEntity(raceId = 1, nfcUid = "AA", number = 1))
        val feed = listOf(FreshBracelet("BB", 2))
        assertTrue(isKnownBracelet("AA", pool, feed))
        assertTrue(isKnownBracelet("BB", pool, feed))
        assertFalse(isKnownBracelet("CC", pool, feed))
    }

    // --- bind failures ---

    @Test
    fun bindFailure_404OnNullNumber_asksNumber() {
        assertEquals(
            MemberProvisionState.NeedsNumber("AA"),
            memberBindFailureState("AA", null, PostResult.Error(404)),
        )
    }

    @Test
    fun bindFailure_404WithNumber_fails() {
        assertEquals(
            MemberProvisionState.Failed("Не найдено на сервере"),
            memberBindFailureState("AA", 5, PostResult.Error(404)),
        )
    }

    @Test
    fun bindFailure_409_boundToAnotherParticipant() {
        assertEquals(
            MemberProvisionState.Failed("Браслет уже привязан к другому участнику"),
            memberBindFailureState("AA", 5, PostResult.Conflict),
        )
    }

    @Test
    fun errorMessage_delegatesOtherCodesToKpMapper() {
        assertEquals(provisionErrorMessage(PostResult.Forbidden), memberProvisionErrorMessage(PostResult.Forbidden))
        assertEquals(provisionErrorMessage(PostResult.Offline), memberProvisionErrorMessage(PostResult.Offline))
        assertEquals("Ошибка сервера", memberProvisionErrorMessage(PostResult.Error(500)))
    }

    // --- server code ---

    @Test
    fun parseServerChipCode_valid() {
        assertArrayEquals(code, parseServerChipCode(codeHex))
        assertArrayEquals(code, parseServerChipCode(codeHex.lowercase()))
    }

    @Test
    fun parseServerChipCode_malformed_null() {
        assertNull(parseServerChipCode("ABCD"))
        assertNull(parseServerChipCode("ZZ" + codeHex.drop(2)))
    }

    // --- write outcome ---

    @Test
    fun writeOutcome_success() {
        assertEquals(
            MemberProvisionState.Success(7),
            memberWriteOutcome("AA", 7, codeHex, ChipWriteResult.Success, MEMBER_WRITE_RETRY_HINT),
        )
    }

    @Test
    fun writeOutcome_wrongType_failsForGood() {
        assertEquals(
            MemberProvisionState.Failed("Это чип КП, а не браслет"),
            memberWriteOutcome("AA", 7, codeHex, ChipWriteResult.WrongType("Это чип КП, а не браслет"), MEMBER_WRITE_RETRY_HINT),
        )
    }

    @Test
    fun writeOutcome_ioFailure_keepsCodePending() {
        assertEquals(
            MemberProvisionState.WaitingForWrite("AA", 7, codeHex, MEMBER_WRITE_RETRY_HINT),
            memberWriteOutcome("AA", 7, codeHex, ChipWriteResult.Failed("lost"), MEMBER_WRITE_RETRY_HINT),
        )
        assertEquals(
            MemberProvisionState.WaitingForWrite("AA", 7, codeHex, MEMBER_WRITE_AGAIN_HINT),
            memberWriteOutcome("AA", 7, codeHex, ChipWriteResult.Unsupported, MEMBER_WRITE_AGAIN_HINT),
        )
    }

    // --- feed / numbers ---

    @Test
    fun feed_newestFirst_dedupedByUid() {
        var feed = addFreshBracelet(emptyList(), "AA", 1)
        feed = addFreshBracelet(feed, "BB", 2)
        feed = addFreshBracelet(feed, "AA", 5)
        assertEquals(listOf(FreshBracelet("AA", 5), FreshBracelet("BB", 2)), feed)
    }

    @Test
    fun feed_capped() {
        var feed = emptyList<FreshBracelet>()
        repeat(MEMBER_FEED_CAP + 5) { feed = addFreshBracelet(feed, "U$it", it + 1) }
        assertEquals(MEMBER_FEED_CAP, feed.size)
        assertEquals("U${MEMBER_FEED_CAP + 4}", feed.first().uid)
    }

    @Test
    fun nextNumber_incrementsOrNullOnOverflow() {
        assertEquals(102, nextMemberNumber(101))
        assertNull(nextMemberNumber(Int.MAX_VALUE))
    }

    @Test
    fun parseMemberNumber_cases() {
        assertEquals(101, parseMemberNumber("101"))
        assertEquals(7, parseMemberNumber(" 007 "))
        assertNull(parseMemberNumber(""))
        assertNull(parseMemberNumber("0"))
        assertNull(parseMemberNumber("+5"))
        assertNull(parseMemberNumber("-5"))
        assertNull(parseMemberNumber("12a"))
        assertNull(parseMemberNumber("99999999999"))
        assertNull(parseMemberNumber("١٢"))
    }
}
