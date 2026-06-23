# Remove debug «Записать code на чип» flow

## Overview
- Remove the DEBUG-only chip-write feature (`WriteChipDialog` + `onTagForWrite` hook + `chipWriterCode` state) from the app.
- It lost relevance: the admin provisioning flow «Привязать чип к КП» (`ProvisioningScreen`) now writes the server-issued K24 `code` onto the chip as part of binding, covering the real workflow.
- Scope is strictly the write feature. The «Отладка» card and its other rows — «Инфо о чипе» (`onReadChipInfo`), «Сбросить команду» (`onResetTeam`), «Очистить БД» (`onClearDatabase`) — stay untouched. Provisioning is not touched.

## Context (from discovery)
- Single-Activity Jetpack Compose app; overlays driven by `rememberSaveable` flags in `MainActivity` (scan-overlay pattern). No ViewModel/Navigation.
- `onTagForWrite` is one of several `@Volatile` NFC reader-mode hooks read from the binder thread in `MainActivity.onTagDiscovered`. Priority chain today: `chipInfo → write → provision → verify → mark → scan`.
- `WriteChipDialog` rendered above `SettingsScreen` while `chipWriterCode != null && showSettings`.
- Files involved:
  - `app/src/main/java/ru/kolco24/kolco24/ui/settings/WriteChipDialog.kt` (delete whole file)
  - `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
  - `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt` (remove orphaned `newChipCode()`)
  - `app/src/test/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriterTest.kt`
- Kept (used by provisioning/scan): `writeChipCode`, `chipCodeHex`, `chipCodeFromHex`, `buildChipRecord`, `parseChipRecord`. `chipCodeHex` is also used by `ScanSession`.

## Development Approach
- **testing approach**: Regular (this is a deletion — adjust existing tests, no new feature tests).
- Single atomic change set; verify with build + unit tests at the end.
- This is mechanical dead-branch removal — no new abstractions, no behavior change to surviving features.
- **CRITICAL: build + unit tests must pass before the task is considered done.**

## Testing Strategy
- **unit tests**: `MifareUltralightWriterTest` is the only affected test. Remove the two tests that are *about* `newChipCode`; in the two tests that merely *use* it as an input, swap to a fixed 16-byte literal so coverage of `chipCodeHex`/`chipCodeFromHex`/write remains intact.
- **e2e tests**: none in this project. The removed UI is DEBUG-only and untested by convention (Compose host UI untested per repo convention); no UI test changes needed.
- Verify nothing else references the removed symbols (grep gate).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix

## Solution Overview
Delete the write-chip code path end to end: the dialog file, the Settings row + param, the `MainActivity` hook/state/effect/priority-branch, and the now-orphaned `newChipCode()` helper. Leave the `Отладка` card visible via its remaining rows and leave the chip-writer primitives that provisioning depends on.

## Technical Details
- After removal the `onTagDiscovered` hook priority chain becomes `chipInfo → provision → verify → mark → scan`.
- The `Отладка` card visibility condition drops `onWriteChip != null`, leaving `onResetTeam != null || onClearDatabase != null || onReadChipInfo != null`.
- `MainActivity` imports to drop: `WriteChipDialog`, `WriteChipState`, **and all five chip symbols** `ChipWriteResult`, `chipCodeFromHex`, `chipCodeHex`, `writeChipCode`, `newChipCode`. Verified: in `MainActivity` these five are used **only** inside the deleted write-chip block (lines 963, 988, 990, 993–995, 1006) — nothing else in the file references them. Provisioning has its own imports in `ProvisioningScreen.kt` (writes the chip there, ~line 70/269), so dropping them from `MainActivity` does not touch provisioning. (`ScanSession` uses `chipCodeHex` via its own import, also unaffected.)

## What Goes Where
- **Implementation Steps**: all code/test edits below (achievable in this repo).
- **Post-Completion**: a manual smoke check on a device with NFC (DEBUG build) to confirm the «Отладка» card still shows the surviving rows and provisioning write still works.

## Implementation Steps

### Task 1: Delete the WriteChipDialog file and Settings wiring

**Files:**
- Delete: `app/src/main/java/ru/kolco24/kolco24/ui/settings/WriteChipDialog.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`

- [x] delete `WriteChipDialog.kt` entirely (`sealed interface WriteChipState` + `@Composable WriteChipDialog`)
- [x] in `SettingsScreen.kt` remove the `onWriteChip: (() -> Unit)? = null` parameter (~line 67)
- [x] remove the `if (onWriteChip != null) { DebugRow(...) }` block (~lines 168–175); also dropped the now-unused `Icons.Filled.Nfc` import
- [x] change the debug-card visibility condition (~line 137) to `onResetTeam != null || onClearDatabase != null || onReadChipInfo != null` (drop `onWriteChip != null`)
- [x] no unit tests for these (Compose UI untested by repo convention) — note in commit

### Task 2: Remove the write-chip hook, state and effect from MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] remove imports `WriteChipDialog`, `WriteChipState` (~lines 100–101) **and all five chip symbols** `ChipWriteResult` (~77), `chipCodeFromHex` (~78), `chipCodeHex` (~79), `newChipCode` (~81), `writeChipCode` (~84) — all are used only inside the deleted block
- [ ] remove `@Volatile var onTagForWrite: ((Tag) -> Unit)? = null` and its KDoc (~line 155)
- [ ] in `onTagDiscovered` remove the `val writeHook = onTagForWrite` branch (~lines 293–297); chain becomes `chipInfo → provision → verify → mark → scan`
- [ ] remove `var chipWriterCode by rememberSaveable { mutableStateOf<String?>(null) }` (~line 432)
- [ ] remove `|| chipWriterCode != null` from the overlay guard (~line 613) and every `chipWriterCode = null` reset (~lines 629, 753, 926, 929, 932, 943, 949, 957)
- [ ] remove the `onWriteChip = if (BuildConfig.DEBUG) { { chipWriterCode = chipCodeHex(newChipCode()) } } else null` argument to `SettingsScreen` (~lines 962–963), leaving `onReadChipInfo`
- [ ] remove the entire `DisposableEffect`/`WriteChipDialog` render block that arms `onTagForWrite` (~lines 976–1009)
- [ ] clean up `onTagForWrite` mentions in the KDoc of neighbouring hooks (`onTagForChipInfo`, `onTagForProvision`, `onTagForVerify`, `onTagForMark`)
- [ ] grep `MainActivity.kt` for `ChipWriteResult`, `chipCodeFromHex`, `chipCodeHex`, `writeChipCode`, `newChipCode` → zero hits remain (all five imports gone, no stray usage)
- [ ] `./gradlew assembleDebug` compiles clean (no unresolved refs, no unused-import lint)

### Task 3: Remove orphaned newChipCode() and fix its tests

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriterTest.kt`

- [ ] remove `fun newChipCode(): ByteArray` (~line 88); do NOT touch `writeChipCode`/`chipCodeHex`/`chipCodeFromHex`/`buildChipRecord`/`parseChipRecord`
- [ ] delete the two tests *about* it: `newChipCode_alwaysReturns16Bytes` (~line 12) and `newChipCode_twoCalls_produceDifferentCodes` (~line 17)
- [ ] in the two tests that merely *use* it as an input, replace `newChipCode()` with a fixed 16-byte `byteArrayOf(...)` literal (reuse the existing 16-byte pattern at lines 47–49 — full-width signed bytes, `0x04` lead):
  - `chipCodeHex_thenFromHex_roundTripForFullRandomCode` (~line 55) — hex roundtrip assertion
  - `parseChipRecord_roundTripWithBuildChipRecord` (~line 154) — parse/build roundtrip assertion (NOT a write-path test)
- [ ] grep the whole test tree (and repo) for `newChipCode` → zero hits remain (else the build fails after the function is removed)
- [ ] `./gradlew testDebugUnitTest` — `MifareUltralightWriterTest` green

### Task 4: Verify acceptance criteria
- [ ] grep repo for `onTagForWrite`, `WriteChipDialog`, `WriteChipState`, `chipWriterCode`, `newChipCode`, `onWriteChip` → zero hits (all removed)
- [ ] confirm surviving debug rows (`onReadChipInfo`, `onResetTeam`, `onClearDatabase`) and provisioning `writeChipCode` are untouched
- [ ] run full suite: `./gradlew lintDebug testDebugUnitTest`

### Task 5: [Final] Update documentation
- [ ] update `CLAUDE.md`: remove the `ui/settings/WriteChipDialog.kt` bullet; trim the `MainActivity.kt` bullet's `onTagForWrite` / `chipWriterCode` / chip-write-flow descriptions and the `SettingsScreen.kt` `onWriteChip` mention; update the hook priority chain to `chipInfo → provision → verify → mark → scan`; note `newChipCode()` removed from `MifareUltralightWriter.kt` bullet
- [ ] grep `CLAUDE.md` for `onTagForWrite`, `WriteChip`, `chipWriterCode`, `onWriteChip`, `newChipCode` → zero stale mentions (the `onTagForWrite` prose appears in several sentences, not just the priority chain)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention — no checkboxes, informational only*

**Manual verification** (DEBUG build, NFC device):
- Open Settings → confirm the «Отладка» card still renders with «Инфо о чипе», «Сбросить команду», «Очистить БД», and the «Записать чип» row is gone.
- Run admin «Привязать чип к КП» on a real chip → confirm binding still writes the code (provisioning write path unaffected).
