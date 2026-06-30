package ru.kolco24.kolco24.ui.scan

/**
 * The three audible/haptic outcomes of a scan tap, played by `ScanFeedbackPlayer`.
 *
 * - [Success] — a valid, recognized chip was read (КП/member; bind ok; check ok; provision written).
 * - [Failure] — a recognized-but-rejected tap (`BadKp`/`UnboundChip`, not-in-pool, check
 *   unknown/inconsistent/no-code, provision/server error).
 * - [Neutral] — a soft "tap registered" tick with no vibration; produced **only** by the non-overlay
 *   paths (unknown idle tag, `AlreadyBound` warn+allow, check tapped before data ready). Never the
 *   result of [feedbackFor] — see below.
 */
enum class ScanFeedbackKind { Success, Failure, Neutral }

/**
 * Pure mapper from a scan-overlay [ScanEvent] to its [ScanFeedbackKind].
 *
 * Exhaustive over the four `ScanEvent` variants (no `else`): [ScanEvent.Kp]/[ScanEvent.Member] →
 * [ScanFeedbackKind.Success], [ScanEvent.UnboundChip]/[ScanEvent.BadKp] → [ScanFeedbackKind.Failure].
 * [ScanFeedbackKind.Neutral] is **intentionally never returned here** — it is a non-overlay outcome
 * produced exclusively by direct `neutral()` calls at the idle/bind/check branch points, so this
 * mapper stays a clean 2-way over `ScanEvent`.
 */
fun feedbackFor(event: ScanEvent): ScanFeedbackKind = when (event) {
    is ScanEvent.Kp, is ScanEvent.Member -> ScanFeedbackKind.Success
    ScanEvent.UnboundChip, is ScanEvent.BadKp -> ScanFeedbackKind.Failure
}
