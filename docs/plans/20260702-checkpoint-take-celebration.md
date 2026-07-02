# Checkpoint-Take Celebration: Fanfare + Отметки Navigation + Coin/Tile Pop-In

## Overview

When a КП is fully taken via NFC (КП chip + all roster members scanned), celebrate:

1. On the completing tap, play a **fanfare** (`checkpoint-mark-completed.wav`, 3.54 s) **instead of** the short success beep, keeping the success vibration.
2. Hold the green «Готово!» screen for the whole fanfare (`SUCCESS_HOLD_MS` 1 800 → 3 500 ms).
3. On the completion auto-close, navigate to the «Отметки» tab (pager page 0) and scroll to the bottom of the tile grid.
4. The new tile pops in with a bouncy scale/fade animation + a **coin** sound (`mark-added-mario.mp3`, 1.85 s).

Sequenced by design: fanfare plays out on the green screen, then the coin fires as the tile pops — no sound overlap.

**Scope exclusions (user-validated in brainstorm):** photo-mark commits and manual tab opens get NO sound/animation. Celebration fires ONLY on the NFC completion auto-close path — never on window expiry or manual close (an incomplete take has no tile anyway).

## Context (from discovery)

- **Assets** already in `app/src/main/res/raw/` (untracked): `checkpoint-mark-completed.wav` (3.54 s stereo 44.1 kHz, decodes to ~625 KB PCM — under SoundPool's per-sample limit), `mark-added-mario.mp3` (1.85 s). Both must be **renamed to underscores** (resource names can't contain dashes). `mark-added.wav` is unused and must be **removed from `res/raw`** (deleted or moved outside `app/src`) — aapt2 rejects hyphenated resource names, and Gradle merges the working-tree `res/` dir regardless of git-tracked status, so merely leaving it untracked breaks the build.
- `data/ScanFeedbackPlayer.kt` — SoundPool adapter (`maxStreams = 2`), existing `shutter()` pattern to follow; `SUCCESS_VIBRATION_PATTERN` already defined.
- `ui/scan/ScanScreen.kt` — `SUCCESS_HOLD_MS = 1_800L` (~line 91); `process()` plays `feedbackFor(event)` *before* `reduce()` (~line 145); completion auto-close `LaunchedEffect(allScanned)` with mutex re-validation (~line 230).
- `MainActivity.kt` — `pagerState` (Отметки = page 0, ~line 489); `closeScanOverlay` shared close path with upload flush (~line 1258); `ScanScreen` call site (~line 1267); `LaunchedEffect(selectedTeamId)` overlay-reset block; existing `switchToTab(page)` helper (~line 1109: `targetPage` guard + haptic + `animateScrollToPage`; its early-return when already on page 0 is fine — the celebration fires via the `celebration` param, not the navigation).
- `ui/marks/MarksScreen.kt` — `LazyColumn` (no `LazyListState` yet) with keyed items: `"metrics"`, optional notices, `"tile_grid"` (a plain non-lazy `Column` grid of `ColorTile`s), optional `"nfc_banner"` below it. The `Mark` tile view-model has **no id field** and is a value `data class` — two identical takes are `==`-equal, so the celebration MUST target the last tile by **flat positional index** (`tiles.lastIndex`), never by `mark == tiles.last()`. `TileGrid` currently iterates `rows.forEach { rowMarks.forEach { ... } }` with no flat index — convert to indexed iteration (`rowIndex * 4 + colIndex`). Pure `marksToTiles` returns tiles oldest-first (new take = last).
- Pure layer untouched: `feedbackFor` (stays a clean 2-way over `ScanEvent`), `isComplete`, `marksToTiles`.

## Development Approach

- **Testing approach**: Regular, per project convention — pure models are JVM-unit-tested; Compose UI and Android adapters (ScanFeedbackPlayer, animation choreography) are **untested by convention**. This change adds no new pure logic; existing pure tests (`ScanFeedbackTest`, `ScanSessionTest`, `MarksMappingTest`) must keep passing.
- Complete each task fully before moving to the next.
- `./gradlew lintDebug` and `./gradlew testDebugUnitTest` must pass before starting the next task.
- **Update this plan file when scope changes during implementation.**

## Testing Strategy

- No new unit tests: every touched surface is Compose choreography or an Android adapter (untested by convention). The transition guard uses the already-tested `isComplete`.
- Regression gate per task: `./gradlew testDebugUnitTest` + `./gradlew lintDebug`.
- End-to-end behavior is verified manually on a device (see Post-Completion) — NFC hardware required.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix

## Solution Overview

**Option A from brainstorm: explicit celebration event via callback + one-shot flag.**

- `ScanFeedbackPlayer` gains `checkpointComplete()` (fanfare + success vibration) and `coin()` (sound only).
- `ScanScreen` decides the tap sound *after* `reduce()`: an incomplete → complete session transition plays the fanfare instead of `feedbackFor(event)`. A new `onCompleted` callback fires only in the completion auto-close branch.
- `MainActivity` owns `pendingCelebration` (plain `remember`, one-shot — a config-change recreate deliberately drops it so a stale celebration never replays) and navigates the pager to page 0.
- `MarksScreen` consumes the event on arrival (`LaunchedEffect(celebration)`): scroll to bottom → coin + last-tile spring pop-in → `onCelebrationDone()`. The effect is keyed on the `celebration` Boolean, so it fires when the flag flips to `true` regardless of whether page 0 was already composed (the pager runs `beyondViewportPageCount = 1`, so it often is).

## Technical Details

- **Fanfare trigger** — in `process()`, inside the mutex, the non-error branch computes `val before = session` (effective session), `session = reduce(...)`, then plays `scanFeedback.checkpointComplete()` iff `!isComplete(before, roster.size) && isComplete(session, roster.size)`, else `scanFeedback.play(feedbackFor(event))`. The `UnboundChip`/`BadKp` branches keep playing failure as today. The transition guard covers: (a) idempotent re-taps during the 3.5 s hold don't restart the fanfare; (b) completion arriving on a **Kp** event (pre-КП buffered members draining into `present`), not only the last Member.
- **Completion callback** — `onCompleted: () -> Unit` param on `ScanScreen` (held via `rememberUpdatedState` like `onClose`); in the completion auto-close effect: `if (shouldClose) { currentOnCompleted(); currentOnClose() }`. The shared `closeScanOverlay` (upload flush) is untouched and still runs on every exit path.
- **Celebration hand-off** — `MainActivity`: `var pendingCelebration by remember { mutableStateOf(false) }`; `onCompleted = { pendingCelebration = true; switchToTab(0) }` (the existing helper); the `LaunchedEffect(selectedTeamId)` overlay-reset block also clears it. New `MarksScreen` params (all defaulted, preview-safe): `celebration: Boolean = false`, `onCelebrationDone: () -> Unit = {}`, `onCoinSound: () -> Unit = {}` (host passes `{ container.scanFeedback.coin() }` — a lambda, not the player).
- **Celebration sequence in MarksScreen** — `LaunchedEffect(celebration)`:
  1. `if (!celebration) return`; if `tiles.isEmpty()` → `onCelebrationDone()`, bail (cold-flow edge; normally the Room write landed ~3.5 s earlier).
  2. Set local `celebratingLast = true` **before** scrolling — `TileGrid` is a plain `Column`, so the last tile composes at scale 0 immediately, no flash.
  3. Scroll the `LazyColumn` (new `rememberLazyListState`) to the very bottom. Target the **LazyColumn item index** (`layoutInfo.totalItemsCount - 1` — the `"nfc_banner"` item may sit below `"tile_grid"`), not the tile count: `animateScrollToItem(lastItemIndex)` then `animateScrollBy` the remaining overshoot of the last item's bottom edge past the viewport (`LazyListItemInfo.size` is the full measured height even when the item exceeds the viewport; clamp ≥ 0 — a no-op when content fits).
  4. Fire `onCoinSound()` and start the pop together — the coin IS the pop. `Animatable` scale 0 → 1 with `Spring.DampingRatioMediumBouncy` + alpha fade, applied via `graphicsLayer` on the last tile only — by flat tile index (`rowIndex * 4 + colIndex == tiles.lastIndex` in `TileGrid`'s converted indexed loop), never by `Mark` equality (value class, duplicates possible).
  5. Clear `celebratingLast`, call `onCelebrationDone()`.
- **Sounds** — new raw resources `checkpoint_mark_completed` / `mark_added_mario`, loaded in `ScanFeedbackPlayer.init` alongside the existing three; `maxStreams` stays 2 (fanfare and coin are sequenced, never concurrent with each other).

## What Goes Where

- **Implementation Steps**: asset rename, the four code files, doc updates — all in this repo.
- **Post-Completion**: on-device manual verification (NFC hardware, real chips).

## Implementation Steps

### Task 1: Sound assets + ScanFeedbackPlayer methods

**Files:**
- Rename: `app/src/main/res/raw/checkpoint-mark-completed.wav` → `checkpoint_mark_completed.wav`
- Rename: `app/src/main/res/raw/mark-added-mario.mp3` → `mark_added_mario.mp3`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/ScanFeedbackPlayer.kt`

- [ ] rename the two asset files to underscore names (plain `mv` — they're untracked); **remove `mark-added.wav` from `res/raw`** (delete or move outside `app/src` — a hyphenated file in `res/raw` fails aapt2 even when git-untracked) and do not commit it
- [ ] load `R.raw.checkpoint_mark_completed` and `R.raw.mark_added_mario` in `ScanFeedbackPlayer.init` (same pattern as the existing three)
- [ ] add `checkpointComplete()`: play fanfare + vibrate `SUCCESS_VIBRATION_PATTERN` (it replaces the success beep on the completing tap, so it keeps the tactile confirmation)
- [ ] add `coin()`: sound only, no vibration (UI flourish, not a chip confirmation) — KDoc both methods in the file's existing style
- [ ] run `./gradlew testDebugUnitTest lintDebug` — must pass before task 2

### Task 2: ScanScreen — hold duration, fanfare on completion transition, onCompleted callback

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`

- [ ] change `SUCCESS_HOLD_MS` from `1_800L` to `3_500L` (comment: matches the fanfare length)
- [ ] in `process()`: move the feedback call after the session update in the non-error branch — capture the effective session before `reduce()`, and play `scanFeedback.checkpointComplete()` iff the session transitioned incomplete → complete (`!isComplete(before, roster.size) && isComplete(session, roster.size)`), else `scanFeedback.play(feedbackFor(event))`; `UnboundChip`/`BadKp` branches keep today's failure feedback (pure `feedbackFor` mapper untouched)
- [ ] add `onCompleted: () -> Unit` param (default `{}`), held via `rememberUpdatedState`; in the completion auto-close effect: `if (shouldClose) { currentOnCompleted(); currentOnClose() }` — expiry and manual close never call it
- [ ] run `./gradlew testDebugUnitTest lintDebug` — must pass before task 3 (`ScanSessionTest`/`ScanFeedbackTest` regression gate)

### Task 3: MainActivity — pendingCelebration flag, navigation, MarksScreen wiring

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `var pendingCelebration by remember { mutableStateOf(false) }` near the overlay flags — plain `remember`, NOT `rememberSaveable` (a recreate mid-celebration deliberately drops it)
- [ ] pass `onCompleted = { pendingCelebration = true; switchToTab(0) }` to the `ScanScreen` call (existing helper, ~line 1109; its already-on-page early-return is fine — the celebration rides the `celebration` param)
- [ ] clear `pendingCelebration = false` in the existing `LaunchedEffect(selectedTeamId)` overlay-reset block (team switch cancels a stale celebration)
- [ ] pass to `MarksScreen`: `celebration = pendingCelebration`, `onCelebrationDone = { pendingCelebration = false }`, `onCoinSound = { container.scanFeedback.coin() }`
- [ ] run `./gradlew testDebugUnitTest lintDebug` — must pass before task 4

### Task 4: MarksScreen — celebration effect, scroll-to-bottom, last-tile pop-in

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] add params `celebration: Boolean = false`, `onCelebrationDone: () -> Unit = {}`, `onCoinSound: () -> Unit = {}` (defaults keep previews/tests working)
- [ ] add `rememberLazyListState` and attach it to the `LazyColumn`
- [ ] add a local `celebratingLast: Boolean` state + the `LaunchedEffect(celebration)` sequence: empty-tiles guard → set `celebratingLast` (tile renders at scale 0 before the scroll — no flash) → animate scroll to the very bottom (`animateScrollToItem(layoutInfo.totalItemsCount - 1)` — LazyColumn **item** index, `"nfc_banner"` may sit below the grid — then overshoot `animateScrollBy` from the last `LazyListItemInfo.size`, clamped ≥ 0) → `onCoinSound()` + start pop animation together → clear `celebratingLast`, `onCelebrationDone()`
- [ ] convert `TileGrid`'s nested `forEach` loops to indexed iteration (flat index = `rowIndex * 4 + colIndex`) and thread the celebration in (e.g. `celebrateLastTile: Boolean`): apply an `Animatable`-driven `graphicsLayer { scaleX/scaleY/alpha }` (`Spring.DampingRatioMediumBouncy`, 0 → 1 + fade) only at flat index `tiles.lastIndex` — never by `Mark` equality (value class, duplicate tiles possible); zero cost for other tiles; `marksToTiles` and all pure logic untouched
- [ ] run `./gradlew testDebugUnitTest lintDebug` — must pass before task 5 (`MarksMappingTest`/`TileFillTest` regression gate)

### Task 5: Verify acceptance criteria

- [ ] verify all Overview requirements are implemented (fanfare replaces beep on the completing tap only; 3.5 s hold; navigation + scroll + pop + coin only on completion auto-close)
- [ ] verify edge cases: completion on a **Kp** event (pre-buffered members); idempotent re-tap during the hold (no fanfare restart); manual back during the hold (no celebration); empty-tiles guard consumes the event; team switch clears `pendingCelebration`
- [ ] `./gradlew assembleDebug` builds clean
- [ ] run full suite: `./gradlew testDebugUnitTest lintDebug`

### Task 6: [Final] Update documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] CLAUDE.md `ScanFeedback.kt` bullet: replace «no "complete" flourish» — completion now plays the fanfare via `checkpointComplete()` (transition-guarded, replaces the beep on the completing tap); mapper still 2-way
- [ ] CLAUDE.md `ScanScreen.kt` / Scan-wiring bullets: «~1 s green Готово beat» → ~3.5 s fanfare-length hold; document `onCompleted` → `pendingCelebration` → MarksScreen consume-on-arrival celebration (coin + last-tile pop) briefly
- [ ] CLAUDE.md `data/ScanFeedbackPlayer.kt` bullet: update the loaded-clip list (add `checkpoint_mark_completed`/`mark_added_mario`, and the already-missing `shutter`) and the new `checkpointComplete()`/`coin()` outcomes
- [ ] CLAUDE.md App-assets bullet: add the two new raw clips; note `mark-added.wav` is not shipped
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

**Manual verification (on-device, NFC hardware + real chips required):**
- Full take flow: КП chip + all member chips → fanfare (no beep on last tap), green screen ≈ fanfare length, lands on «Отметки» scrolled to bottom, new tile pops with coin.
- Members-first order (buffer before КП) → completion on the КП tap plays the fanfare.
- Manual back during the green hold → no navigation/coin; window expiry with a partial take → no celebration.
- Volume sanity: fanfare/coin loudness vs the existing beeps on a real speaker (`USAGE_ASSISTANCE_SONIFICATION` channel).
