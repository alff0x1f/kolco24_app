package ru.kolco24.kolco24.ui.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.UploadResultKind.Error
import ru.kolco24.kolco24.data.track.UploadResultKind.Offline
import ru.kolco24.kolco24.data.track.UploadResultKind.Ok
import ru.kolco24.kolco24.data.track.UploadTarget

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmLoopTest {

    private val cloud = UploadTarget.Cloud

    private suspend fun TestScope.confirm(
        attempt: suspend () -> UploadResultKind,
        states: MutableList<ConfirmState>,
    ): Boolean = runConfirm(cloud, attempt, { states += it }, elapsedNow = { testScheduler.currentTime })

    @Test
    fun firstTryOk_confirms() = runTest {
        val states = mutableListOf<ConfirmState>()
        var calls = 0
        assertTrue(confirm({ calls++; Ok }, states))
        assertEquals(listOf<ConfirmState>(ConfirmState.Sending(cloud, 1)), states)
        assertEquals(1, calls)
    }

    @Test
    fun failOnceThenOk_retriesAfter3s() = runTest {
        val states = mutableListOf<ConfirmState>()
        val attemptTimes = mutableListOf<Long>()
        val results = ArrayDeque(listOf(Offline, Ok))
        assertTrue(confirm({ attemptTimes += testScheduler.currentTime; results.removeFirst() }, states))
        assertEquals(listOf(ConfirmState.Sending(cloud, 1), ConfirmState.Sending(cloud, 2)), states)
        assertEquals(listOf(0L, CONFIRM_RETRY_MS), attemptTimes)
    }

    @Test
    fun alwaysOffline_failsOfflineAfterTimeout() = runTest {
        val states = mutableListOf<ConfirmState>()
        assertFalse(confirm({ Offline }, states))
        assertEquals(ConfirmState.Failed(cloud, offline = true), states.last())
        assertTrue(testScheduler.currentTime >= CONFIRM_TIMEOUT_MS)
        // attempts at 0, 3, 6, ..., 18, 21 s → the one at 21 s is past the deadline → Failed
        assertEquals(8, states.count { it is ConfirmState.Sending })
        assertEquals(21_000L, testScheduler.currentTime)
    }

    @Test
    fun alwaysError_failsNotOffline() = runTest {
        val states = mutableListOf<ConfirmState>()
        assertFalse(confirm({ Error }, states))
        assertEquals(ConfirmState.Failed(cloud, offline = false), states.last())
    }

    @Test
    fun lastResultDecidesOfflineFlag() = runTest {
        val states = mutableListOf<ConfirmState>()
        confirm({ if (testScheduler.currentTime >= 18_000L) Error else Offline }, states)
        assertEquals(ConfirmState.Failed(cloud, offline = false), states.last())
    }

    @Test
    fun lastResultDecidesOfflineFlag_mirror() = runTest {
        val states = mutableListOf<ConfirmState>()
        confirm({ if (testScheduler.currentTime >= 18_000L) Offline else Error }, states)
        assertEquals(ConfirmState.Failed(cloud, offline = true), states.last())
    }

    @Test
    fun failsExactlyAtDeadline() = runTest {
        // timeout 6 s, retry 3 s → attempts at 0, 3, 6 s; the failed attempt at t = 6000 hits the
        // deadline exactly (elapsed == timeout) and must fail there (`>=`), not retry at 9 s.
        val states = mutableListOf<ConfirmState>()
        val attemptTimes = mutableListOf<Long>()
        val ok = runConfirm(
            target = cloud,
            attempt = { attemptTimes += testScheduler.currentTime; Offline },
            onState = { states += it },
            elapsedNow = { testScheduler.currentTime },
            timeoutMs = 6_000L,
            retryMs = 3_000L,
        )
        assertFalse(ok)
        assertEquals(listOf(0L, 3_000L, 6_000L), attemptTimes)
        assertEquals(6_000L, testScheduler.currentTime)
        assertEquals(ConfirmState.Failed(cloud, offline = true), states.last())
    }

    @Test
    fun cancelDuringRetryDelay_stopsWithoutTerminalState() = runTest {
        val states = mutableListOf<ConfirmState>()
        var calls = 0
        val job = launch { confirm({ calls++; Offline }, states) }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_000L)
        job.cancel()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf<ConfirmState>(ConfirmState.Sending(cloud, 1)), states)
    }

    @Test
    fun cancelDuringInFlightAttempt_noTerminalState() = runTest {
        val states = mutableListOf<ConfirmState>()
        var calls = 0
        val gate = CompletableDeferred<UploadResultKind>()
        val job = launch { confirm({ calls++; gate.await() }, states) }
        runCurrent()
        assertEquals(1, calls)
        job.cancel()
        gate.complete(Ok)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf<ConfirmState>(ConfirmState.Sending(cloud, 1)), states)
    }

    @Test
    fun attemptReturningOkAfterDeadline_confirms() = runTest {
        val states = mutableListOf<ConfirmState>()
        val ok = confirm({
            kotlinx.coroutines.delay(CONFIRM_TIMEOUT_MS + 5_000L)
            Ok
        }, states)
        assertTrue(ok)
        assertEquals(listOf<ConfirmState>(ConfirmState.Sending(cloud, 1)), states)
        assertTrue(testScheduler.currentTime > CONFIRM_TIMEOUT_MS)
    }

    @Test
    fun statusText() {
        assertEquals(
            "Отправка на сервер… (попытка 1)",
            confirmStatusText(ConfirmState.Sending(UploadTarget.Cloud, 1)),
        )
        assertEquals(
            "Отправка на локальный сервер… (попытка 3)",
            confirmStatusText(ConfirmState.Sending(UploadTarget.Local, 3)),
        )
        assertEquals("Нет связи — КП не подтверждён", confirmStatusText(ConfirmState.Failed(cloud, offline = true)))
        assertEquals("Сервер не принял — КП не подтверждён", confirmStatusText(ConfirmState.Failed(cloud, offline = false)))
    }
}
