package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.MemberTagEntity

class JudgeScanModelTest {

    private fun memberTag(uid: String, number: Int): MemberTagEntity =
        MemberTagEntity(raceId = 1, nfcUid = uid, number = number)

    @Test
    fun classify_poolNotReady_shortCircuitsEvenWhenMemberTagWouldMatch() {
        val result = classifyJudgeScan(
            uid = "0411223344AABB",
            memberTag = memberTag("0411223344AABB", number = 123),
            hasKpCode = false,
            poolReady = false,
        )
        assertEquals(JudgeScanResult.PoolNotReady, result)
    }

    @Test
    fun classify_poolNotReady_shortCircuitsEvenWithKpCode() {
        val result = classifyJudgeScan(
            uid = "DEADBEEF",
            memberTag = null,
            hasKpCode = true,
            poolReady = false,
        )
        assertEquals(JudgeScanResult.PoolNotReady, result)
    }

    @Test
    fun classify_pooledUid_isRecorded() {
        val result = classifyJudgeScan(
            uid = "0411223344AABB",
            memberTag = memberTag("0411223344AABB", number = 123),
            hasKpCode = false,
            poolReady = true,
        )
        assertEquals(JudgeScanResult.Recorded(uid = "0411223344AABB", number = 123), result)
    }

    @Test
    fun classify_pooledUid_winsOverKpCode() {
        val result = classifyJudgeScan(
            uid = "0411223344AABB",
            memberTag = memberTag("0411223344AABB", number = 7),
            hasKpCode = true,
            poolReady = true,
        )
        assertEquals(JudgeScanResult.Recorded(uid = "0411223344AABB", number = 7), result)
    }

    @Test
    fun classify_notInPool_withKpCode_isKpChip() {
        val result = classifyJudgeScan(
            uid = "DEADBEEF",
            memberTag = null,
            hasKpCode = true,
            poolReady = true,
        )
        assertEquals(JudgeScanResult.KpChip, result)
    }

    @Test
    fun classify_notInPool_noCode_isUnknownChip() {
        val result = classifyJudgeScan(
            uid = "DEADBEEF",
            memberTag = null,
            hasKpCode = false,
            poolReady = true,
        )
        assertEquals(JudgeScanResult.UnknownChip("DEADBEEF"), result)
    }
}
