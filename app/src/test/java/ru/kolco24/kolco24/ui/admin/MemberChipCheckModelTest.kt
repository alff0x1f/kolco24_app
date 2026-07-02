package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.MemberTagEntity

class MemberChipCheckModelTest {

    private fun memberTag(uid: String, number: Int): MemberTagEntity =
        MemberTagEntity(raceId = 1, nfcUid = uid, number = number)

    @Test
    fun classify_uidInPool_isOk() {
        val result = classifyMemberChipCheck(
            uid = "0411223344AABB",
            memberTag = memberTag("0411223344AABB", number = 123),
            hasKpCode = false,
        )
        assertEquals(MemberChipCheckResult.Ok(uid = "0411223344AABB", number = 123), result)
    }

    @Test
    fun classify_uidInPool_winsOverKpCode() {
        // The server-synced pool is authoritative: a pooled UID is Ok even if the chip also carries a code.
        val result = classifyMemberChipCheck(
            uid = "0411223344AABB",
            memberTag = memberTag("0411223344AABB", number = 7),
            hasKpCode = true,
        )
        assertEquals(MemberChipCheckResult.Ok(uid = "0411223344AABB", number = 7), result)
    }

    @Test
    fun classify_notInPool_withKpCode_isKpChip() {
        val result = classifyMemberChipCheck(
            uid = "DEADBEEF",
            memberTag = null,
            hasKpCode = true,
        )
        assertEquals(MemberChipCheckResult.KpChip("DEADBEEF"), result)
    }

    @Test
    fun classify_notInPool_noCode_isUnknown() {
        val result = classifyMemberChipCheck(
            uid = "DEADBEEF",
            memberTag = null,
            hasKpCode = false,
        )
        assertEquals(MemberChipCheckResult.Unknown("DEADBEEF"), result)
    }
}
