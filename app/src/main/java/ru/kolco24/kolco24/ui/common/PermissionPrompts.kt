package ru.kolco24.kolco24.ui.common

import ru.kolco24.kolco24.data.DenialLogUpdate

/*
 * Pure permission-routing decisions (Android-free, JVM-tested by `PermissionPromptsTest`).
 * `MainActivity` gathers the inputs (`shouldShowRequestPermissionRationale`, the persisted
 * "denied before" flags of `PermissionRequestLog`) and acts on the result.
 *
 * Requests are **always launched**; "permanent denial" is decided from the launcher RESULT, never
 * guessed before launch. `shouldShowRequestPermissionRationale` is `false` before the first ever
 * request, after a permanent denial, after a dismissed dialog (Android leaves the state unchanged)
 * and after the system auto-resets an unused app's permissions — so a pre-launch "persisted flag +
 * no rationale" guess wrongly routed an auto-reset or dismissed permission to settings. The
 * persisted flag therefore records a **real denial** (the result left a rationale, or the user
 * picked «Примерная»), not a request: a dismissed dialog records nothing, and a grant clears it
 * (auto-reset only revokes granted permissions, so a reset permission starts from a clean flag).
 *
 * **Silent-denial session fallback.** A permission that is already permanently denied with a clean
 * flag (denied under an older build, or a denial the flag never saw) makes the system answer every
 * request instantly — no UI, no grant, no rationale — so the flag alone would never route it anywhere.
 * `silentDenialBefore` = an earlier *explicit* (user-tapped, not the first-scan auto-ask) request this
 * session already came back as such a silent denial ([locationSilentDenial] / [notificationSilentDenial]).
 * The second silent denial in a session is then treated as permanent: it routes to the settings dialog
 * and `Set`s the persisted flag. A single silent result (a dismissed dialog looks identical) never routes.
 */

/** What to surface after a location request returns. */
enum class LocationPrompt { None, DeniedDialog, PreciseDialog }

/**
 * `true` = a FINE+COARSE result the system may have answered with no UI (or a dismissed dialog): FINE not
 * granted and no rationale left for the permission(s) still denied. Counted into the session fallback
 * only for explicit requests.
 */
fun locationSilentDenial(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    rationaleFine: Boolean,
    rationaleCoarse: Boolean,
): Boolean = !fineGranted && !rationaleFine && (coarseGranted || !rationaleCoarse)

/**
 * Flag update after a FINE+COARSE request returns (rationales read after the result). FINE granted
 * clears it. A rationale for either permission means the user really denied it; COARSE-only means
 * the user picked «Примерная» (a real FINE denial) — both set it. Otherwise (a dismissed dialog, or an
 * already-permanent denial the system answered instantly) the flag is left as is — unless it is the
 * second silent denial this session ([silentDenialBefore]), which is treated as permanent and sets it.
 */
fun locationDenialLogUpdate(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    rationaleFine: Boolean,
    rationaleCoarse: Boolean,
    silentDenialBefore: Boolean = false,
): DenialLogUpdate = when {
    fineGranted -> DenialLogUpdate.Clear
    coarseGranted || rationaleFine || rationaleCoarse -> DenialLogUpdate.Set
    silentDenialBefore -> DenialLogUpdate.Set
    else -> DenialLogUpdate.Keep
}

/**
 * Decision after a FINE+COARSE request returns. [deniedBefore] is the persisted flag snapshot taken
 * *before* this request. [wasApproximate] = COARSE was already granted before the request (an
 * upgrade-to-precise attempt): only then does a COARSE-only result with no FINE rationale mean "the
 * system answered instantly" → [LocationPrompt.PreciseDialog]. Without it, a user who just picked
 * «Примерная» in the system dialog after an earlier full denial would get the settings dialog right
 * over their choice. [unprompted] = the request was the silent first-scan auto-ask (not a user tap):
 * it never surfaces a dialog — it would land over the live scan overlay mid-take, every session for a
 * permanently-denied user (the system answers instantly with no UI). [silentDenialBefore] (see the
 * file header) stands in for [deniedBefore] when the flag missed the real denial.
 */
fun locationResultPrompt(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    deniedBefore: Boolean,
    wasApproximate: Boolean,
    rationaleFine: Boolean,
    rationaleCoarse: Boolean,
    unprompted: Boolean = false,
    silentDenialBefore: Boolean = false,
): LocationPrompt {
    val permanent = deniedBefore || silentDenialBefore
    return when {
        unprompted || fineGranted -> LocationPrompt.None
        !coarseGranted ->
            if (permanent && !rationaleFine && !rationaleCoarse) LocationPrompt.DeniedDialog else LocationPrompt.None
        wasApproximate && permanent && !rationaleFine -> LocationPrompt.PreciseDialog
        else -> LocationPrompt.None
    }
}

/** `true` = a `POST_NOTIFICATIONS` denial with no rationale (answered with no UI, or a dismissed prompt). */
fun notificationSilentDenial(granted: Boolean, rationale: Boolean): Boolean = !granted && !rationale

/**
 * `POST_NOTIFICATIONS` flag update after a request returns: grant clears, a denial leaving a rationale
 * sets, and so does the second silent denial this session ([silentDenialBefore], see the file header).
 */
fun notificationDenialLogUpdate(granted: Boolean, rationale: Boolean, silentDenialBefore: Boolean = false): DenialLogUpdate = when {
    granted -> DenialLogUpdate.Clear
    rationale -> DenialLogUpdate.Set
    silentDenialBefore -> DenialLogUpdate.Set
    else -> DenialLogUpdate.Keep
}

/**
 * `POST_NOTIFICATIONS` result → `true` = show the dismissible «open notification settings?» dialog
 * (never a direct `startActivity` from the callback: a second real «Don't allow» lands here too, and
 * must not be overridden by an unexplained settings screen). Denied with no rationale although a real
 * denial was recorded before this request ([deniedBefore] snapshot) or an earlier explicit request this
 * session was already a silent denial ([silentDenialBefore]). A single dismissed prompt with nothing
 * recorded keeps the system dialog available for the next tap.
 */
fun notificationResultShowsSettingsDialog(
    granted: Boolean,
    deniedBefore: Boolean,
    rationale: Boolean,
    silentDenialBefore: Boolean = false,
): Boolean = !granted && !rationale && (deniedBefore || silentDenialBefore)
