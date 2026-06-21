# Admin: Проверка чипов КП (chip verification)

## Overview
- Add a read-only **«Проверка чипов КП»** feature to the Администратор section. When the admin taps a КП chip, the app shows which КП the chip is bound to and whether everything is OK (plus diagnostic info).
- Solves the organizer's need to field-verify provisioned chips: confirm a chip points at the expected КП, spot blank/foreign/stale chips before the race.
- Integrates as a new full-screen overlay opened from AdminHome, mirroring the existing `ProvisioningScreen` overlay pattern. **Identity-only, fully offline** — no network, no admin token for the lookup, no chip writes.

## Context (from discovery)
- **Files/components involved:**
  - `ui/admin/AdminScreen.kt` — AdminHome rows (`AdminActionRow`, `onOpenProvisioning`).
  - `ui/admin/ProvisioningScreen.kt` — overlay + NFC hook lifecycle + `internal fun CheckpointColor.barColor()` + `ScanZone`/pulse pattern (reference + reuse `barColor`).
  - `ui/admin/ProvisioningModel.kt` — pure-model pattern to mirror.
  - `ui/scan/ScanSession.kt` + `ui/scan/ScanScreen.kt` — pure `classifyTag` + `Mutex` serialization of taps (reference).
  - `MainActivity.kt` — `onTagDiscovered` priority chain (line ~260), admin overlay hosting + `showAdmin`/`showProvisioning` flags + `selectedRaceId`.
  - `data/nfc/MifareUltralightWriter.kt` — `readChipCode(tag): ByteArray?` (blocking NfcA read; returns null for blank/foreign/IO error).
  - `data/crypto/LegendCrypto.kt` — `bid(code): String` (pure; already tested).
  - `data/LegendRepository.kt` — `tagsForRace(raceId): Flow<List<TagEntity>>`, `checkpointsForRace(raceId): Flow<List<CheckpointEntity>>`.
  - `data/db/TagEntity.kt` — fields `raceId, bid, point, checkMethod, iv, ct`. `data/db/CheckpointEntity.kt` — `id, number, cost, color, …`.
- **Related patterns found:**
  - Pure Android-free model + JVM unit test (`ProvisioningModel.kt` + `ProvisioningModelTest`, `ScanSession.kt` + tests).
  - Overlay flag in `MainActivity`, rendered in the same `Box`, with a guarded `BackHandler`; flag reset in `onScanClick` and `LaunchedEffect(selectedTeamId)`.
  - `DisposableEffect(raceId)` arms `(context as MainActivity).onTagForVerify` and clears in `onDispose`.
- **Dependencies identified:** none new — reuses `tagsForRace`, `checkpointsForRace`, `readChipCode`, `LegendCrypto.bid`, `normalizeNfcUid`, `CheckpointColor`/`barColor`. **No** Room migration, DAO, repo, API, or DTO changes.

## Development Approach
- **Testing approach**: Regular (code first, then tests) — pure model gets unit tests; Compose host is untested per repo convention (see `ProvisioningScreen` note).
- Complete each task fully before moving to the next; small, focused changes.
- **Every code task includes new/updated tests** where there is testable logic (the pure model). UI/wiring tasks have no unit tests by repo convention — verification is the build + lint + manual NFC check.
- **All tests must pass before starting the next task.** Run `./gradlew testDebugUnitTest` after model changes; `./gradlew lintDebug` before finishing.
- Maintain backward compatibility (additive only).

## Testing Strategy
- **Unit tests**: `ChipCheckModelTest` for the pure classifier — covers `Ok`, `UnknownChip`, `Inconsistent`, `NoCode`.
- **e2e tests**: project has none. Compose hosts are untested by convention (no Robolectric on classpath; see `ProvisioningScreen` doc comment). NFC behavior requires a physical device — covered in Post-Completion manual verification.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Keep this plan in sync with actual work.

## Solution Overview
- **Data path (identity-only):** the verify overlay collects `tagsForRace(raceId)` and `checkpointsForRace(raceId)` for `selectedRaceId`. Per chip tap (serialized by a `Mutex`): `uid = normalizeNfcUid(tag.id)`; `code = withContext(IO){ readChipCode(tag) }`; if `code != null` then `bid = LegendCrypto.bid(code)`, find the matching tag **in the already-collected list** (`tags.firstOrNull { it.bid == bid }`), resolve `checkpointsById[tag.point]`, and the per-КП chip count from `tags.groupingBy { it.point }.eachCount()`. No `tagDao` call, no `unlock()`, no decrypt, no `reveal()` side effect, no server.
- **Pure classifier** decides the result from already-resolved values → testable in isolation. Host owns the impure NFC + Flow reads.
- **Why local/offline:** verification needs chip→КП identity, which the synced legend tags already provide. Decrypting (`unlock`) would mutate the legend (`reveal`) and add failure surface for no benefit; a server round-trip would re-bind and require connectivity in the field.
- **Why transient `remember` state (not app-scoped like provisioning):** verification has no in-flight server/write job to survive rotation, so losing the last result + recent-scan log on rotation is acceptable and keeps the host simple (no `pendingCleanup`/app-scope machinery).

## Technical Details
- **`ChipCheckResult`** (sealed interface, pure):
  - `Ok(uid: String, number: Int, cost: Int?, color: CheckpointColor?, bid: String, checkMethod: String, chipsOnKp: Int)` — bid matched a tag whose checkpoint exists.
  - `UnknownChip(uid: String, bid: String)` — code read, but no tag with that bid in this race.
  - `Inconsistent(uid: String, bid: String, point: Int)` — tag found but no checkpoint row for `point`.
  - `NoCode(uid: String)` — `readChipCode` returned null (blank chip, member bracelet, or read error — collapsed, since `readChipCode` can't distinguish).
- **Pure classify signature** (host resolves DB values, passes them in):
  ```kotlin
  fun classifyChipCheck(
      uid: String,
      bid: String?,                 // null when code couldn't be read
      tag: TagEntity?,              // tags.firstOrNull { it.bid == bid }
      checkpoint: CheckpointEntity?,// checkpointsById[tag.point]
      chipsOnKp: Int,
  ): ChipCheckResult
  ```
  Logic: `bid == null` → `NoCode`; `tag == null` → `UnknownChip`; `checkpoint == null` → `Inconsistent`; else `Ok`.
- **`onTagDiscovered` chain** becomes: `onTagForWrite → onTagForProvision → onTagForVerify → onTagForMark → onTagScanned → idle`. Only one admin hook is armed at a time (provisioning vs verify never co-open), so relative order is cosmetic, but verify needs the raw `Tag`.
- **Recent-scans log:** `List<ChipCheckResult>` in `remember`, newest first, capped ~20 (`take(20)` on prepend).

## What Goes Where
- **Implementation Steps** (`[ ]`): pure model + test, Compose screen, `MainActivity` hook/flag/overlay wiring, `AdminScreen` row, lint/test verification.
- **Post-Completion** (no checkboxes): manual on-device NFC verification (real chips: provisioned КП chip, blank chip, member bracelet, foreign-race chip), CLAUDE.md note.

## Implementation Steps

### Task 1: Pure chip-check model + classifier

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ChipCheckModel.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/admin/ChipCheckModelTest.kt`

- [ ] Create `ChipCheckModel.kt` (package `ru.kolco24.kolco24.ui.admin`, Android-free — only imports `CheckpointColor`, `TagEntity`, `CheckpointEntity`), define the `ChipCheckResult` sealed interface with the four variants from Technical Details.
- [ ] Implement `classifyChipCheck(uid, bid, tag, checkpoint, chipsOnKp): ChipCheckResult` per the branch logic; map `checkpoint.color` via `parseCheckpointColor(...)` into `Ok.color`.
- [ ] KDoc the file mirroring `ProvisioningModel.kt`'s tone (pure, JVM-unit-testable, host does the impure reads).
- [ ] Write `ChipCheckModelTest` success case: tag + checkpoint present → `Ok` with correct number/cost/color/bid/checkMethod/chipsOnKp.
- [ ] Write `ChipCheckModelTest` edge cases: `bid == null` → `NoCode`; `tag == null` → `UnknownChip`; `checkpoint == null` → `Inconsistent`.
- [ ] Run `./gradlew testDebugUnitTest` — must pass before next task.

### Task 2: CheckChipScreen Compose overlay

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/CheckChipScreen.kt`

- [ ] Create stateful `CheckChipScreen(raceId: Int?, onClose: () -> Unit, modifier: Modifier = Modifier)` mirroring `ProvisioningScreen`'s scaffold: `Column` + tap-swallowing `pointerInput`, `TopAppBar("Проверка чипов КП")` with back arrow → `onClose`; `raceId == null` → centered hint «Сначала выберите команду».
- [ ] Collect `container.legendRepository.tagsForRace(raceId)` and `checkpointsForRace(raceId)` (filter stale rows by `raceId` like `ProvisioningScreen`); build `checkpointsById` and `countsByPoint = tags.groupingBy { it.point }.eachCount()`.
- [ ] Hold `var lastResult by remember(raceId) { mutableStateOf<ChipCheckResult?>(null) }`, `val recent = remember(raceId) { mutableStateListOf<ChipCheckResult>() }`, a `remember { Mutex() }` to serialize taps, and `val scope = rememberCoroutineScope()` (**commit to `rememberCoroutineScope`, not `applicationScope`** — verify has no in-flight write to survive rotation; a composition-scoped launch is cancelled on dispose so a late `readChipCode` result can't write stale `lastResult`/`recent`).
- [ ] `DisposableEffect(raceId)`: arm `(context as MainActivity).onTagForVerify = { tag -> scope.launch { mutex.withLock { … } } }`. The hook fires on the main thread (`mainHandler.post` in `onTagDiscovered`) and the scope is Main-confined, so only `readChipCode` needs `withContext(Dispatchers.IO){ … }`; the state writes after it resumes are already on Main (no manual `withContext(Main)`). Inside the lock: `uid = normalizeNfcUid(tag.id); code = withContext(IO){ readChipCode(tag) }; bid = code?.let { LegendCrypto.bid(it) }`; resolve `tag`/`cp` from the collected lists; `result = classifyChipCheck(...)`; then `lastResult = result; recent.add(0, result); while (recent.size > 20) recent.removeAt(recent.lastIndex)` (use `removeAt(lastIndex)`, **not** `removeLast()` — the stdlib extension can trip a `NewApi`/minSdk-24 lint error against JDK 21 `SequencedCollection.removeLast`). Clear `onTagForVerify = null` in `onDispose`.
- [ ] Build the **hero result card**: idle (null) → pulsing NFC glyph + «Приложите чип КП» (reuse the pulse approach from `ProvisioningScreen.ScanZone`); `Ok` → color band via `CheckpointColor.barColor()` (reuse the existing `internal` fun in `ProvisioningScreen.kt`, same package — do **not** copy) + big `RobotoMono` КП number + cost + green ✓ «Привязан корректно» + secondary rows (mono UID, «На этом КП ещё N чипов» where N = `chipsOnKp - 1` floored at 0, small «bid · checkMethod»); `UnknownChip` → amber «Чип не привязан к КП этой гонки (другая гонка или устаревший список)» + uid + bid; `Inconsistent` → red «КП №{point} нет в легенде — обновите данные»; `NoCode` → amber «Нет кода КП: пустой чип, браслет участника или ошибка чтения — приложите ещё раз» + uid.
- [ ] Build the **«Недавние проверки»** list under the hero (newest first, from `recent`): per-row color dot + «КП NN»/«—» + status icon + uid tail (`chipTokenLabel`).
- [ ] No unit test (Compose host, untested by repo convention); verify it compiles via Task 5 build.

### Task 3: Wire the NFC verify hook in MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] Add `@Volatile var onTagForVerify: ((Tag) -> Unit)? = null` near `onTagForProvision` (with a KDoc mirroring the others — armed by `CheckChipScreen`, read-only verification, needs raw `Tag`).
- [ ] In `onTagDiscovered`, insert the `onTagForVerify` branch **after** the `onTagForProvision` block and before `onTagForMark` (post to `mainHandler` and `return`, same shape as siblings).
- [ ] Confirm build (no test — wiring). Covered by Task 5.

### Task 4: AdminHome row + overlay hosting

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/AdminScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] `AdminScreen.kt`: add `onOpenCheckChip: () -> Unit = {}` param; thread it into `AdminHome`; add a new `AdminActionRow` (icon `Icons.Filled.FactCheck` or `Search`, title «Проверить чип КП», subtitle «Узнать, к какому КП привязан чип») → `onOpenCheckChip`, placed above the «Выйти» row.
- [ ] `MainActivity.kt`: add `var showCheckChip by rememberSaveable { mutableStateOf(false) }` near `showProvisioning`.
- [ ] `MainActivity.kt`: pass `onOpenCheckChip = { showProvisioning = false; showCheckChip = true }` to `AdminScreen` (reset the sibling sub-overlay for symmetry with the `showSettings = false` pattern; AdminHome only opens one at a time, so this is defensive); add `showCheckChip = false` to `AdminScreen`'s `onClose`.
- [ ] `MainActivity.kt`: render `if (showCheckChip && !showScan) { CheckChipScreen(raceId = selectedRaceId, onClose = { showCheckChip = false }) }` after the `ProvisioningScreen` block (drawn above AdminScreen); add a guarded `BackHandler(enabled = showCheckChip && !showScan && teamFlowStep == TeamFlowStep.None && confirmTeamId == null) { showCheckChip = false }` registered after admin's, and add `!showCheckChip` to the admin `BackHandler`'s `enabled` guard so the verify handler wins when stacked.
- [ ] `MainActivity.kt`: reset `showCheckChip = false` everywhere the other admin overlays reset — `onScanClick` (line ~552 + the `showScan` `BackHandler` at ~589), `LaunchedEffect(selectedTeamId)` (line ~465), and add `!showCheckChip` to the bind/unbind `BackHandler`/render guards (lines ~913/919/1046/1048) alongside `!showAdmin && !showProvisioning`.
- [ ] No unit test (UI wiring). Covered by Task 5.

### Task 5: Verify acceptance criteria
- [ ] Verify all Overview requirements: scanning a provisioned КП chip in the Admin section shows its КП (number/cost/color), an OK status, UID, count of other chips on that КП, and bid·method diagnostics.
- [ ] Verify edge cases handled: blank chip / member bracelet → `NoCode`; foreign-race or stale chip → `UnknownChip`; missing checkpoint row → `Inconsistent`.
- [ ] Run `./gradlew lintDebug` — must pass.
- [ ] Run `./gradlew testDebugUnitTest` — must pass (incl. `ChipCheckModelTest`).
- [ ] Confirm `./gradlew assembleDebug` builds.

### Task 6: [Final] Documentation
- [ ] Add a `ui/admin/CheckChipScreen.kt` + `ui/admin/ChipCheckModel.kt` bullet to CLAUDE.md's data/UI layer notes, and note the new `onTagForVerify` hook + `showCheckChip` overlay flag in the `MainActivity` / Admin-overlay sections (mirroring the provisioning entries).
- [ ] Move this plan to `docs/plans/completed/`.

## Post-Completion
*Items requiring manual intervention or external systems — informational only*

**Manual verification (physical device required — NFC):**
- Provisioned КП chip → shows correct КП number/cost/color + «Привязан корректно».
- Blank/unwritten chip and a member bracelet → `NoCode` message.
- Chip provisioned for a different race (or before a legend resync) → `UnknownChip`.
- Continuous scanning: tap several chips in a row → hero updates each time, «Недавние проверки» accumulates newest-first and caps at ~20.
- Back button and the «Назад» arrow both close the overlay; opening the scan FAB or switching team dismisses it.
