# Open «Отметить КП» on chip scan + auto-close

## Overview
Scanning a checkpoint (КП) chip or a bound team-member chip should **open** the «Отметить КП»
overlay (`ScanScreen`) and feed that first tap into the session. The overlay should **auto-close**
when the 20 s scan window expires, or when the КП chip plus all team members have been scanned
(completion), after a brief success beat.

Problem it solves: today the overlay opens only via the orange FAB. An idle КП tap just shows a
Toast, an idle member tap does nothing, and the overlay never closes on expiry or completion (the
user must tap «Готово»). This makes the primary marking flow — walk up to a КП, tap it, tap the
team's bracelets — require manual app navigation first.

Integrates with the existing NFC reader-mode + `NDEF_DISCOVERED` cold-launch infrastructure and the
existing incremental-persistence scan path (`MarkRepository.startKpTake` / `addMember`), which
already scores takes as scans land — so closing the overlay is pure UI dismissal, no final commit.

## Context (from discovery)
- Files/components involved:
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — NFC dispatch (`onTagDiscovered`,
    `handleNfcIntent`), the `showScan` overlay host, the `onScanTag` processor lambda
    (~lines 659–754), `ScanTakeState`, the `onTagFor*` hook fields (~lines 125–157).
  - `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt` — the overlay; arms
    `onTagForMark`, owns the UI `session` + the expiry `LaunchedEffect` + `finalizeSession()`.
  - `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt` — pure `ScanSession` / `reduce` /
    `classifyTag` / `ScanEvent` / `SCAN_WINDOW_MS`. **Stays pure; `reduce`/`classifyTag` unchanged.**
- Related patterns found:
  - `@Volatile` hook fields read on the binder thread, written from main via `DisposableEffect`
    (`onTagForMark`, `onTagScanned`, `onTagForProvision`, `onTagForVerify`, `onTagForWrite`).
  - Overlay-as-state pattern: `rememberSaveable` booleans + `BackHandler`, no nav library.
  - `SelectedTeamState` sealed (`None`/`Loading`/`Missing`/`Present`) already drives team resolution.
  - `scanBindings: Map<String,Int>` (uid → numberInTeam, roster-filtered) already exists in the host.
  - Pure scan logic is unit-tested in `app/src/test/.../ui/scan/ScanSessionTest.kt` and
    `ScanTagDecisionTest.kt`.
- Dependencies identified: no data-layer / Room migration changes. Scoring is already incremental.

## Development Approach
- **Testing approach**: Regular (code first, then tests) — UI/Activity wiring dominates; the only
  unit-testable new logic is a pure completion helper, tested in the existing `ScanSessionTest`.
- Complete each task fully before the next; small, focused changes.
- **Every code task includes tests where there is pure/unit-testable logic.** Most of this change is
  Compose/Activity NFC wiring with no JVM-unit-test seam (consistent with repo convention: hosts are
  untested, pure models are tested). The new pure `isComplete` helper is the explicit test target.
- All tests must pass before starting the next task. Run after each change.
- Maintain backward compatibility: the FAB open path, `reduce`/`classifyTag`, and existing tests
  must keep working unchanged.

## Testing Strategy
- **Unit tests**: extend `ScanSessionTest` for the new pure `isComplete(session, rosterSize)` helper
  (success: КП + full roster present → true; edge: no КП → false, partial roster → false, roster 0 →
  false).
- **e2e tests**: none in this project (no Playwright/Cypress). Manual NFC verification is listed
  under Post-Completion (requires physical chips + device).
- Build/lint gates: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `./gradlew lintDebug`.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- New tasks get a ➕ prefix; blockers get a ⚠️ prefix.
- Update this plan if implementation deviates from scope.

## Solution Overview
**Unified "captured scan" model.** All three open-the-overlay entry points converge on one captured
`(code, uid)` pair (no live `Tag` needed downstream):

| Entry point | Where | Source of code/uid |
|---|---|---|
| Live idle tap (app open) | `onTagDiscovered` idle branch (binder thread) | `readChipCode(tag)` + `normalizeNfcUid(tag.id)` (already read for recognition) |
| Warm launch (КП tap, backgrounded) | `onNewIntent` → `handleNfcIntent` | `chipCodeFromNdef(...)` + `EXTRA_ID` |
| Cold launch (КП tap, closed) | `onCreate` (first launch) → `handleNfcIntent` | same as warm |

Cold/warm are always КП chips (only КП chips are written with NDEF+AAR via `writeChipCodeNdef`;
member bracelets are raw page bytes and cannot cold-launch). Member bracelets only ever arrive via
the live idle path.

All three set a single Activity-level `MutableStateFlow<CapturedScan?> nfcLaunchScan`
(`CapturedScan(code: ByteArray?, uid: String)`; `code == null` ⇒ member bracelet). A host collector
decides what to do based on `SelectedTeamState`. The opening tap is delivered into `ScanScreen` via a
`pendingScan` field that the overlay drains once, processed as `ScanInput.Captured` — the **same**
`unlock → classifyTag → persist → ScanEvent` path used by in-overlay taps.

**Key simplification:** the live idle tap already reads the chip on the binder thread for
recognition, so we stash that `(code, uid)` directly — **no raw-`Tag` replay, no double read.**

**Auto-close** lives in `ScanScreen`: expiry adds `onClose()` to the existing finalize; completion
adds a new `LaunchedEffect(allScanned)` with a ~1 s "Готово!" success beat then close.

### Approach rationale (settled in brainstorm)
- Unifying live + warm + cold through one `CapturedScan` flow (vs. special-casing each) avoids a raw
  `Tag` replay path and a double chip read, and gives cold-launch a clean deferral while Room emits
  the selected team (`SelectedTeamState.Loading`).
- A `ScanInput` sealed type lets in-overlay taps keep reading the live tag while the opener uses the
  already-captured `(code, uid)`, with a single shared processor body.

## Technical Details

### New types (in `MainActivity.kt`)
```kotlin
data class CapturedScan(val code: ByteArray?, val uid: String, val capturedAt: Long)

sealed interface ScanInput {
    data class Live(val tag: Tag) : ScanInput        // in-overlay subsequent taps
    data class Captured(val code: ByteArray?, val uid: String) : ScanInput  // the opening tap
}
```
> **`now: Long` is load-bearing.** Today's processor is `suspend (Tag, Long) -> ScanEvent` — the
> `now` is captured at tap time (in `ScanScreen`'s `DisposableEffect`) and threaded through; it drives
> window-expiry detection (`expired = lastScanAt != 0L && now - lastScanAt >= SCAN_WINDOW_MS`). The
> refactored processor keeps the timestamp: signature `suspend (ScanInput, now: Long) -> ScanEvent`.
> For a `Captured` opening tap, `now` is the moment the tag was captured (`CapturedScan.capturedAt`,
> stamped on the binder thread / in `handleNfcIntent`), **not** drain time — a cold launch can drain
> seconds after the actual tap, so reusing `System.currentTimeMillis()` at drain time would corrupt
> the window.
> Note: `CapturedScan` holds a `ByteArray`; if structural equality is ever needed, override
> `equals`/`hashCode`. As a `StateFlow` payload that is set/cleared by reference, default identity is
> acceptable — keep it simple unless a test needs value equality.

### New Activity fields
- `val nfcLaunchScan = MutableStateFlow<CapturedScan?>(null)` — single entry point for all three
  open sources. Thread-safe; set from binder thread and main.
- `@Volatile var boundUidsSnapshot: Set<String> = emptySet()` — bound member uids for the selected
  team, read on the binder thread to recognize bracelets without touching Compose state.
- `@Volatile var pendingScan: CapturedScan? = null` — the opening tap handed to `ScanScreen` to drain
  once on arm.

### Processor refactor
Extract the current inline `onScanTag` lambda body (MainActivity ~659–754) into a processor that
accepts `ScanInput` **and** the tap-time `now`:
- signature `suspend (ScanInput, now: Long) -> ScanEvent` (keeps the existing `now` threading).
- `Live(tag)` → `code = withContext(IO){ readChipCode(tag) }`, `uid = normalizeNfcUid(tag.id)`.
- `Captured(code, uid)` → use directly (skip `readChipCode`).
- Shared tail (unchanged): refuse if `raceId/teamId == null || scanRoster.isEmpty()` →
  `BadKp("команда не выбрана")`; `unlock(raceId, code)` when `code != null`;
  re-read `checkpointsSnapshot` for Revealed/IdentityOnly; `classifyTag(...)`; window-expiry +
  `startKpTake`/`addMember` bookkeeping via `scanTake`; return the `ScanEvent`.

### Drain / hook shared body (`ScanScreen.kt`)
The diagnostic surfacing + expired-session discard currently live **inline** in the
`onTagForMark` arming closure (not in `onScanTag` itself): on event, `UnboundChip`/`BadKp` set
`diagnostic`; else clear `diagnostic`, discard a UI session whose window already elapsed at tap time,
and `reduce(...)`. Factor this into a local `suspend fun process(input: ScanInput, now: Long)` that
does `scanMutex.withLock { currentOnScanTag(input, now); … reduce … }`, called by **both** the
`onTagForMark` hook (wrapping the live tag as `ScanInput.Live`) and the `pendingScan` drain (wrapping
as `ScanInput.Captured` with `now = capturedAt`). This keeps the opening tap byte-for-byte identical
to subsequent taps.

### Pure helper (in `ScanSession.kt`)
```kotlin
fun isComplete(session: ScanSession?, rosterSize: Int): Boolean =
    session?.point != null && rosterSize > 0 && session.present.size >= rosterSize
```
Used by `ScanScreen`'s completion effect (and unit-tested). It mirrors the **shape** of
`MarkRepository`'s `complete = present.size >= expectedCount` for a **UI-close decision only** — the
DB's `present`-set (`scanTake` / the persisted mark row) and the UI `ScanSession.present` are two
independent sets updated on different paths. They agree in the normal flow, but scoring is already
persisted incrementally and is **independent** of this helper. Closing the overlay never finalizes
or gates scoring; it is purely cosmetic. (Pre-КП members live in `bufferedBeforeKp` and are drained
into `present` when the КП lands, so `point != null` is the correct guard.)

### Processing flow
1. **Recognition (live idle)** — `onTagDiscovered` idle branch: `code = readChipCode(tag)`,
   `uid = normalizeNfcUid(tag.id)`, `now = System.currentTimeMillis()`;
   `recognized = code != null || uid in boundUidsSnapshot`; on hit
   `nfcLaunchScan.value = CapturedScan(code, uid, now)`; else drop. Replaces the Toast.
2. **`handleNfcIntent` (cold/warm)** — extract `code = chipCodeFromNdef(ndefMessagesOf(intent))`,
   `uid = normalizeNfcUid(intent.getByteArrayExtra(NfcAdapter.EXTRA_ID))`; if both present
   `nfcLaunchScan.value = CapturedScan(code, uid, System.currentTimeMillis())`. Replaces the Toast.
3. **Snapshot updater** — host `LaunchedEffect(scanBindings) { activity.boundUidsSnapshot = scanBindings.keys }`.
   `boundUidsSnapshot` is a **coarse open-gate** only (opens the overlay); it may briefly lag a team
   switch, so a stale-team bracelet uid could open an overlay that then reads `UnboundChip` — the
   authoritative roster-filtered `scanBindings` still governs scoring. Acceptable; КП taps are
   recognized via `code != null` regardless.
4. **Dispatcher** — a **`LaunchedEffect` keyed on `(captured, teamState)`** (NOT a bare
   `collectAsState` + inline `if` — side effects must not run during composition, and the dispatch
   must re-run when `teamState` transitions `Loading → Present`). Read `captured` via
   `nfcLaunchScan.collectAsState()`. Inside the effect, branch on `SelectedTeamState`:
   - busy overlay up (`teamFlowStep != None || confirmTeamId != null || showSettings || showAdmin ||
     showProvisioning || showCheckChip || bindSlot != null || unbindSlot != null ||
     chipWriterCode != null`) → drop + clear flow. (Includes `showSettings`/`confirmTeamId` so a tap
     mid-Settings/confirm doesn't yank the user out; the live-idle binder path already can't reach
     here while a bind/provision/verify hook is armed.)
   - `Present` → reset overlays (same resets as `onScanClick`), `pendingScan = captured;
     showScan = true`, clear flow.
   - `None`/`Missing` → `pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker`,
     clear flow.
   - `Loading` → **wait**: return without clearing the flow. Because the effect is keyed on
     `teamState`, the `Loading → Present` transition re-runs it and the deferred cold-launch tap then
     opens the overlay (the race the design solves).
5. **Drain** — `ScanScreen`'s arm-`onTagForMark` `DisposableEffect`: after arming, if
   `activity.pendingScan != null`, run it once through the shared `process(...)` body (Drain / hook
   shared body above) as a `ScanInput.Captured` with `now = pendingScan.capturedAt`, then set
   `pendingScan = null`.

### Auto-close (in `ScanScreen.kt`)
- **Expiry**: the existing expiry branch finalizes inside `scanMutex.withLock { if (session?.lastScanAt
  == lastScanAt) finalizeSession() }`. Track whether finalize actually fired (e.g. a local `val
  finalized = …`) and call `currentOnClose()` **after** the `withLock` block, only when it fired — so
  the close never holds the mutex. Use `val currentOnClose by rememberUpdatedState(onClose)` so the
  captured callback stays fresh. A FAB-opened overlay with a `null` session has no running timer →
  stays open until manual close (unchanged).
- **Completion**: new `LaunchedEffect(allScanned)` where
  `allScanned = isComplete(session, roster.size)`. Guard the body with `if (allScanned && !completed)`,
  set `completed = true` **before** the delay (so a recomposition during the hold can't double-close),
  swap the hero card to a green "Готово!" check state, `delay(SUCCESS_HOLD_MS)` (~1000 ms), then
  `finalizeSession()` + `currentOnClose()`. Add `private const val SUCCESS_HOLD_MS = 1_000L`. Reset
  `completed = false` in `finalizeSession()`.

## What Goes Where
- **Implementation Steps** (`[ ]`): all code + the one unit test, build/lint/test gates.
- **Post-Completion** (no checkboxes): on-device NFC verification with physical КП + member chips
  (live foreground, warm relaunch, cold launch), which cannot be automated here.

## Implementation Steps

### Task 1: Pure `isComplete` helper + test

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanSessionTest.kt`

- [ ] add `fun isComplete(session: ScanSession?, rosterSize: Int): Boolean` to `ScanSession.kt`
      (`point != null && rosterSize > 0 && present.size >= rosterSize`)
- [ ] write test: КП set + `present` covers full roster → `true`
- [ ] write tests for edge cases: `null` session → false; `point == null` (only buffered) → false;
      partial roster → false; `rosterSize == 0` → false; `present` larger than roster → true
- [ ] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 2: `CapturedScan` / `ScanInput` types + `ScanInput`-keyed processor

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add top-level `data class CapturedScan(code: ByteArray?, uid: String, capturedAt: Long)` and
      `sealed interface ScanInput { Live(tag), Captured(code, uid) }`
- [ ] refactor the inline `onScanTag` lambda body into a processor `suspend (ScanInput, now: Long) ->
      ScanEvent` (preserve the existing `now` threading): `Live` reads
      `readChipCode`/`normalizeNfcUid`; `Captured` uses code+uid directly; shared tail
      (unlock → classifyTag → startKpTake/addMember bookkeeping) unchanged
- [ ] update `ScanScreen`'s `onTagForMark` call site to wrap the live tag as `ScanInput.Live(tag)` and
      pass the tap-time `now`
- [ ] confirm `reduce`/`classifyTag` and `ScanTagDecisionTest`/`ScanSessionTest` are untouched
- [ ] `./gradlew assembleDebug` compiles; `./gradlew testDebugUnitTest` still green

### Task 3: Idle recognition + cold/warm intent → `nfcLaunchScan`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `val nfcLaunchScan = MutableStateFlow<CapturedScan?>(null)` and
      `@Volatile var boundUidsSnapshot: Set<String> = emptySet()` to `MainActivity`
- [ ] rewrite `onTagDiscovered` idle branch: read `code`+`uid`, stamp `now`,
      `recognized = code != null || uid in boundUidsSnapshot`; on hit
      `nfcLaunchScan.value = CapturedScan(code, uid, now)`; else drop (remove the Toast)
- [ ] rewrite `handleNfcIntent`: extract `code` via `chipCodeFromNdef(ndefMessagesOf(intent))` and
      `uid` via `normalizeNfcUid(intent.getByteArrayExtra(NfcAdapter.EXTRA_ID))`; if both present set
      `nfcLaunchScan.value = CapturedScan(code, uid, System.currentTimeMillis())` (remove the Toast);
      keep null-safety when `EXTRA_ID` absent
- [ ] `./gradlew assembleDebug` compiles; `./gradlew lintDebug` clean (no `NewApi`/unused warnings)

### Task 4: Host collector, snapshot updater, and `pendingScan` plumbing

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `@Volatile var pendingScan: CapturedScan? = null` to `MainActivity`
- [ ] add `LaunchedEffect(scanBindings) { activity.boundUidsSnapshot = scanBindings.keys }` in the host
- [ ] read `captured` via `nfcLaunchScan.collectAsState()`, then add a **`LaunchedEffect(captured,
      teamState)`** dispatcher (side effects must not run in composition; keying on `teamState` makes
      the cold-launch `Loading → Present` deferral re-fire) branching on `SelectedTeamState`:
      busy-overlay (incl. `showSettings`/`confirmTeamId`) → drop+clear; `Present` → reset overlays +
      `pendingScan = captured; showScan = true` + clear; `None`/`Missing` → open comp picker + clear;
      `Loading` → return without clearing
- [ ] ensure the `Present` branch performs the same overlay resets as `onScanClick`
- [ ] `./gradlew assembleDebug` compiles

### Task 5: `ScanScreen` drains `pendingScan`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`

- [ ] extract the inline `onTagForMark` body (diagnostic + expired-session discard + `reduce`) into a
      local `suspend fun process(input: ScanInput, now: Long)` used by both the hook and the drain
- [ ] in the arm-`onTagForMark` `DisposableEffect`, after arming, if `activity.pendingScan != null`
      call `process(ScanInput.Captured(it.code, it.uid), it.capturedAt)` once, then set
      `activity.pendingScan = null`
- [ ] verify a fresh `scanTake`/`session` per overlay open still holds (the captured tap seeds them)
- [ ] `./gradlew assembleDebug` compiles

### Task 6: Auto-close on expiry and completion + success beat

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`

- [ ] add `private const val SUCCESS_HOLD_MS = 1_000L`, `val currentOnClose by rememberUpdatedState(onClose)`,
      and a `var completed` flag (reset to `false` in `finalizeSession()`)
- [ ] expiry branch: track whether `finalizeSession()` fired inside the `scanMutex.withLock`, then call
      `currentOnClose()` **after** the `withLock` block (only when it fired) — never hold the mutex
      across the close
- [ ] add `LaunchedEffect(allScanned)` where `allScanned = isComplete(session, roster.size)`: guard
      `if (allScanned && !completed)`, set `completed = true` before `delay(SUCCESS_HOLD_MS)`, then
      `finalizeSession()` + `currentOnClose()`
- [ ] swap the hero card to a green "Готово!" check state while `completed`
- [ ] confirm the FAB-opened-with-no-scan path (null session) does not auto-close

### Task 7: Verify acceptance criteria
- [ ] КП-first idle tap opens the overlay with the КП identified; member taps then complete it
- [ ] member-first idle tap (bound bracelet) opens the overlay and buffers the member
- [ ] unrecognized/foreign tag idle → nothing happens (no overlay)
- [ ] idle tap with no team selected → comp picker opens
- [ ] window expiry → overlay closes; КП + all members → success beat → overlay closes
- [ ] run full suite: `./gradlew testDebugUnitTest`
- [ ] `./gradlew assembleDebug` and `./gradlew lintDebug` pass

### Task 8: [Final] Docs + plan move
- [ ] update `CLAUDE.md` (NFC infra + Scan overlay sections) to describe the `nfcLaunchScan` /
      `CapturedScan` / `ScanInput` open path and the expiry/completion auto-close
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification (physical NFC required, cannot be automated here):**
- Live foreground: app open on any tab, tap a КП chip → «Отметить КП» opens with КП identified; tap
  each team bracelet → success beat → auto-close. Repeat member-first (bracelet then КП).
- Warm relaunch: background the app, tap a КП chip → app foregrounds via `onNewIntent` and the
  overlay opens.
- Cold launch: fully close the app, tap a КП chip → app launches via `NDEF_DISCOVERED` and the
  overlay opens once the selected team resolves from Room (verify the `Loading` deferral).
- No-team device: clear the selected team, tap a КП chip → comp/team picker opens.
- Expiry: open via a tap, wait out the 20 s window → overlay closes.
- Foreign card: tap a non-КП, non-bound NFC card while idle → nothing happens.
