package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.MemberTagEntity
import ru.kolco24.kolco24.data.time.ClockStatus

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

    @Test
    fun clockAnchored_noSync_isFalse() {
        assertFalse(clockAnchored(ClockStatus.NoSync))
    }

    @Test
    fun clockAnchored_ok_isTrue() {
        assertTrue(clockAnchored(ClockStatus.Ok))
    }

    @Test
    fun clockAnchored_skewed_isTrue() {
        // A skewed-but-anchored clock still counts as anchored: the NoSync action succeeded (an anchor
        // now exists), even though the wall-clock disagrees with it.
        assertTrue(clockAnchored(ClockStatus.Skewed(skewMs = 120_000)))
    }

    @Test
    fun noSyncActionMessage_mapsEveryOutcome() {
        assertEquals("Время подтверждено", noSyncActionMessage(NoSyncActionOutcome.Anchored))
        assertEquals(
            "Сеть недоступна — попробуйте ещё раз",
            noSyncActionMessage(NoSyncActionOutcome.NetworkUnreachable),
        )
        assertEquals("Нет доступа к геолокации", noSyncActionMessage(NoSyncActionOutcome.LocationDenied))
        assertEquals("Не удалось получить сигнал GPS", noSyncActionMessage(NoSyncActionOutcome.NoGpsFix))
    }
}
