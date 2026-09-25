package ru.kolco24.kolco24.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.DenialLogUpdate

class PermissionPromptsTest {

    // --- persisted "denied before" flag updates ---

    @Test
    fun `location grant clears the flag`() {
        assertEquals(DenialLogUpdate.Clear, locationDenialLogUpdate(fineGranted = true, coarseGranted = true, rationaleFine = false, rationaleCoarse = false))
    }

    @Test
    fun `location denial leaving a rationale or picking approximate sets the flag`() {
        assertEquals(DenialLogUpdate.Set, locationDenialLogUpdate(fineGranted = false, coarseGranted = false, rationaleFine = true, rationaleCoarse = false))
        assertEquals(DenialLogUpdate.Set, locationDenialLogUpdate(fineGranted = false, coarseGranted = false, rationaleFine = false, rationaleCoarse = true))
        assertEquals(DenialLogUpdate.Set, locationDenialLogUpdate(fineGranted = false, coarseGranted = true, rationaleFine = false, rationaleCoarse = false))
    }

    @Test
    fun `dismissed location dialog records nothing`() {
        // Dismissed first ask (or after an auto-reset): state unchanged, no rationale — must not become
        // a recorded denial, else the next tap would be routed to settings instead of the dialog.
        assertEquals(DenialLogUpdate.Keep, locationDenialLogUpdate(fineGranted = false, coarseGranted = false, rationaleFine = false, rationaleCoarse = false))
    }

    @Test
    fun `notification flag updates`() {
        assertEquals(DenialLogUpdate.Clear, notificationDenialLogUpdate(granted = true, rationale = false))
        assertEquals(DenialLogUpdate.Set, notificationDenialLogUpdate(granted = false, rationale = true))
        // Swiped-away prompt: permission state unchanged, nothing recorded.
        assertEquals(DenialLogUpdate.Keep, notificationDenialLogUpdate(granted = false, rationale = false))
    }

    @Test
    fun `dismiss then permanent denial end to end`() {
        // Replays the persisted flag + the session silent-denial flag as MainActivity does.
        var denied = false
        var silentThisSession = false
        fun request(granted: Boolean, rationale: Boolean): Boolean {
            val silentBefore = silentThisSession
            val shows = notificationResultShowsSettingsDialog(granted, denied, rationale, silentBefore)
            when (notificationDenialLogUpdate(granted, rationale, silentBefore)) {
                DenialLogUpdate.Set -> denied = true
                DenialLogUpdate.Clear -> denied = false
                DenialLogUpdate.Keep -> Unit
            }
            if (notificationSilentDenial(granted, rationale)) silentThisSession = true
            return shows
        }
        fun newSession() { silentThisSession = false }
        assertFalse(request(granted = false, rationale = false)) // first prompt swiped away: no routing
        newSession()
        assertFalse(request(granted = false, rationale = true)) // real first denial
        assertTrue(request(granted = false, rationale = false)) // second denial → permanent → dismissible dialog
        assertTrue(request(granted = false, rationale = false)) // system answers instantly → dialog again
        assertFalse(request(granted = true, rationale = false)) // granted in settings
        newSession()
        assertFalse(request(granted = false, rationale = false)) // auto-reset + dismissed once: no dialog
    }

    // --- result callback ---

    private fun result(
        fine: Boolean = false,
        coarse: Boolean = false,
        deniedBefore: Boolean = true,
        wasApproximate: Boolean = false,
        rationaleFine: Boolean = false,
        rationaleCoarse: Boolean = false,
        unprompted: Boolean = false,
        silentDenialBefore: Boolean = false,
    ) = locationResultPrompt(fine, coarse, deniedBefore, wasApproximate, rationaleFine, rationaleCoarse, unprompted, silentDenialBefore)

    @Test
    fun `first-scan auto-ask never yields a dialog`() {
        // Permanently denied: the system answers instantly with no UI; without the unprompted guard this
        // is DeniedDialog over the live scan overlay on every cold start.
        for (fine in listOf(false, true)) for (coarse in listOf(false, true))
            for (ever in listOf(false, true)) for (approx in listOf(false, true))
                for (rf in listOf(false, true)) for (rc in listOf(false, true)) {
                    assertEquals(
                        LocationPrompt.None,
                        result(fine, coarse, ever, approx, rf, rc, unprompted = true),
                    )
                }
        // Sanity: the same inputs from an explicit tap still route to settings.
        assertEquals(LocationPrompt.DeniedDialog, result())
    }

    @Test
    fun `fine granted shows nothing`() {
        assertEquals(LocationPrompt.None, result(fine = true, coarse = true, wasApproximate = true))
    }

    @Test
    fun `full denial after an earlier real denial with no rationale is permanent`() {
        assertEquals(LocationPrompt.DeniedDialog, result())
    }

    @Test
    fun `first ever full denial shows nothing`() {
        assertEquals(LocationPrompt.None, result(deniedBefore = false))
        assertEquals(LocationPrompt.None, result(rationaleFine = true, rationaleCoarse = true))
    }

    @Test
    fun `coarse only after an upgrade attempt with no fine rationale is the precise dialog`() {
        assertEquals(LocationPrompt.PreciseDialog, result(coarse = true, wasApproximate = true))
    }

    @Test
    fun `picking approximate in the system dialog never raises the precise dialog`() {
        // First request fully denied, second time the user picks «Примерная»: FINE now has two denials
        // (no rationale), but COARSE was not granted before this request.
        assertEquals(LocationPrompt.None, result(coarse = true, wasApproximate = false))
    }

    @Test
    fun `upgrade attempt with fine rationale or no prior denial shows nothing`() {
        assertEquals(LocationPrompt.None, result(coarse = true, wasApproximate = true, rationaleFine = true))
        assertEquals(LocationPrompt.None, result(coarse = true, wasApproximate = true, deniedBefore = false))
    }

    // --- notifications ---

    @Test
    fun `notifications show the settings dialog only for a denial after a recorded real denial with no rationale`() {
        assertTrue(notificationResultShowsSettingsDialog(granted = false, deniedBefore = true, rationale = false))
        // Swiped-away first prompt: nothing recorded before → dialog stays available.
        assertFalse(notificationResultShowsSettingsDialog(granted = false, deniedBefore = false, rationale = false))
        assertFalse(notificationResultShowsSettingsDialog(granted = false, deniedBefore = true, rationale = true))
        assertFalse(notificationResultShowsSettingsDialog(granted = true, deniedBefore = true, rationale = false))
    }

    // --- silent-denial session fallback (already permanently denied, clean persisted flag) ---

    @Test
    fun `silent denial classification`() {
        assertTrue(locationSilentDenial(fineGranted = false, coarseGranted = false, rationaleFine = false, rationaleCoarse = false))
        assertTrue(locationSilentDenial(fineGranted = false, coarseGranted = true, rationaleFine = false, rationaleCoarse = false))
        assertFalse(locationSilentDenial(fineGranted = true, coarseGranted = true, rationaleFine = false, rationaleCoarse = false))
        assertFalse(locationSilentDenial(fineGranted = false, coarseGranted = false, rationaleFine = true, rationaleCoarse = false))
        assertFalse(locationSilentDenial(fineGranted = false, coarseGranted = false, rationaleFine = false, rationaleCoarse = true))
        assertFalse(locationSilentDenial(fineGranted = false, coarseGranted = true, rationaleFine = true, rationaleCoarse = false))
        assertTrue(notificationSilentDenial(granted = false, rationale = false))
        assertFalse(notificationSilentDenial(granted = false, rationale = true))
        assertFalse(notificationSilentDenial(granted = true, rationale = false))
    }

    @Test
    fun `second silent denial in a session sets the flag`() {
        assertEquals(
            DenialLogUpdate.Set,
            locationDenialLogUpdate(fineGranted = false, coarseGranted = false, rationaleFine = false, rationaleCoarse = false, silentDenialBefore = true),
        )
        assertEquals(DenialLogUpdate.Set, notificationDenialLogUpdate(granted = false, rationale = false, silentDenialBefore = true))
        // A grant still clears.
        assertEquals(
            DenialLogUpdate.Clear,
            locationDenialLogUpdate(fineGranted = true, coarseGranted = true, rationaleFine = false, rationaleCoarse = false, silentDenialBefore = true),
        )
        assertEquals(DenialLogUpdate.Clear, notificationDenialLogUpdate(granted = true, rationale = false, silentDenialBefore = true))
    }

    @Test
    fun `clean flag and already permanently denied location routes on the second tap`() {
        assertEquals(LocationPrompt.None, result(deniedBefore = false)) // first tap: could be a dismissed dialog
        assertEquals(LocationPrompt.DeniedDialog, result(deniedBefore = false, silentDenialBefore = true))
        // Upgrade attempt from Approximate with FINE already permanently denied.
        assertEquals(LocationPrompt.PreciseDialog, result(coarse = true, wasApproximate = true, deniedBefore = false, silentDenialBefore = true))
        // Still never the precise dialog unless the request started from Approximate.
        assertEquals(LocationPrompt.None, result(coarse = true, wasApproximate = false, deniedBefore = false, silentDenialBefore = true))
        // The auto-ask stays silent regardless.
        assertEquals(LocationPrompt.None, result(deniedBefore = false, silentDenialBefore = true, unprompted = true))
    }

    @Test
    fun `clean flag and already permanently denied location end to end`() {
        // Replays MainActivity's scan-launcher bookkeeping: persisted flag + session silent flag.
        var denied = false
        var silentThisSession = false
        fun tap(): LocationPrompt {
            val silentBefore = silentThisSession
            val prompt = result(deniedBefore = denied, silentDenialBefore = silentBefore)
            when (locationDenialLogUpdate(false, false, false, false, silentBefore)) {
                DenialLogUpdate.Set -> denied = true
                DenialLogUpdate.Clear -> denied = false
                DenialLogUpdate.Keep -> Unit
            }
            if (locationSilentDenial(false, false, false, false)) silentThisSession = true
            return prompt
        }
        assertEquals(LocationPrompt.None, tap())
        assertEquals(LocationPrompt.DeniedDialog, tap())
        assertTrue(denied)
        // New session: the persisted flag now routes on the first tap.
        silentThisSession = false
        assertEquals(LocationPrompt.DeniedDialog, tap())
    }

    @Test
    fun `clean flag and already permanently denied notifications show the dialog on the second tap`() {
        assertFalse(notificationResultShowsSettingsDialog(granted = false, deniedBefore = false, rationale = false))
        assertTrue(notificationResultShowsSettingsDialog(granted = false, deniedBefore = false, rationale = false, silentDenialBefore = true))
        assertFalse(notificationResultShowsSettingsDialog(granted = true, deniedBefore = false, rationale = false, silentDenialBefore = true))
        assertFalse(notificationResultShowsSettingsDialog(granted = false, deniedBefore = false, rationale = true, silentDenialBefore = true))
    }
}
