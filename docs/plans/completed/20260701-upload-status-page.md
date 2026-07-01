# «Загрузка данных» — consolidated upload-status page

## Overview
Move all upload-status reporting off the individual tabs and onto one dedicated
overlay page reached from the «Прочее» section of the Команда tab. The page shows
three sections — **Отметки** (take-row metadata), **Фото** (photo frames), **GPS-трек**
(track points) — each split into two upload targets («Интернет» = cloud, «Финиш» = LAN).

Problem it solves: today upload status is scattered across two tabs (a collapsible
receipt pill in `TrackCard` on Команда, an always-expanded footer panel at the bottom
of `MarksScreen` on Отметки), photo-frame progress is invisible (folded into the marks
counts), and the same `ReceiptLine` UI is copy-duplicated in two files. One page becomes
the single source of truth and net-removes the duplication.

Integrates via the existing overlay pattern (a `rememberSaveable` flag rendered after
`Scaffold`, own `BackHandler`), reuses the existing `TrackUploadStatus`/`TargetLine`
view-models and `markUploadOutcomes`/`trackUploadOutcomes` maps, and adds two new
read-only DAO counters. No Room schema change.

## Context (from discovery)
- **Entry point**: `ui/team/TeamScreen.kt` → `MiscSection` (`SectionCard(title = "Прочее")`)
  currently holds a single `MiscRow(icon = Settings, label = "Настройки", … isLast = true,
  onClick = onOpenSettings)`. `TeamScreen` takes `onOpenSettings: () -> Unit` (param, line 80).
- **Overlay wiring**: `MainActivity.kt` — `showSettings by rememberSaveable` (line 470),
  the settings overlay + `BackHandler` block (lines ~1330-1367), the deeper-overlay busy guard
  (line ~966), and the two `showSettings` reset points — the captured-scan/`onScanClick` block
  (line ~981) and `closeScanOverlay` (line ~1165). `showSettings` is **not** reset in
  `LaunchedEffect(selectedTeamId)` (line 758); `showUpload` mirrors that.
- **Counter derivations**: `MainActivity.kt` lines 632-687 derive `trackUploadStatus` and
  `marksUploadStatus` via scoped `produceState` + the collected outcome maps
  (`container.trackUploadOutcomes`, `container.markUploadOutcomes`). Passed into
  `MarksScreen(uploadStatus = marksUploadStatus)` (line 1113) and
  `TeamScreen(trackUploadStatus = trackUploadStatus)` (line 1150).
- **View-models**: `TargetLine`/`TrackUploadStatus` live in `ui/track/TrackCard.kt`
  (lines 75-84); imported into `MainActivity` and `MarksScreen`. `UploadCounts(total, local,
  cloud)` is in `data/db/TrackDao.kt` (line 22).
- **Marks counter**: `MarkDao.uploadCounts` (lines 115-123) already requires metadata **AND**
  frames (`uploadedX AND (photoPath IS NULL OR photosUploadedX)`). `MarkRepository.uploadCounts`
  (line 99) just delegates. Frame state is per-mark (`photosUploadedLocal/Cloud` flags); frame
  paths are a JSON list in `photoPath`. Pure codec `photoPaths(String?)` in
  `data/marks/PhotoPaths.kt` (tested).
- **Removals**: `TrackCard.kt` upload UI (`UploadStatusRow`, `CloudReceiptPill`,
  `UploadReceiptCard`, `ReceiptLine`, `outcomeLabelRu`, the `uploadStatus` param + render sites);
  `MarksScreen.kt` `MarksUploadPanel` + its `ReceiptLine`/`outcomeLabelRu` copy + `uploadStatus`
  param + `item("upload_status")` block.

## Development Approach
- **testing approach**: Regular (code first, then tests) — pure logic and DAO queries.
- Per repo convention (CLAUDE.md): **only pure models and DAO/trust-boundaries are tested**;
  Compose screens and Android adapters are untested. So `UploadScreen` itself gets no test;
  the pure fold, the pure «Финиш»-visibility predicate, and the new DAO queries do.
- Complete each task fully (including its tests) before the next; all tests pass before moving on.
- No Room schema change → **no migration, no `schemas/<n>.json` bump**.

## Testing Strategy
- **unit tests** (JVM, `app/src/test/...`): the photo-frame fold (queried rows →
  `TrackUploadStatus`/`UploadCounts`) and the «Финиш»-visibility predicate.
- **instrumented tests** (`app/src/androidTest/...`): add the two new DAO queries to the
  existing `MarkDaoTest`.
- **not tested** (convention): `UploadScreen` composable, the `MiscRow` addition, `MainActivity`
  wiring.
- Commands: `./gradlew testDebugUnitTest` (unit), `./gradlew connectedDebugAndroidTest`
  (instrumented — requires emulator/device), `./gradlew lintDebug` (must pass before merge).

## Progress Tracking
- mark completed items `[x]` immediately; add ➕ for new tasks, ⚠️ for blockers.
- keep this file in sync if scope shifts during implementation.

## Solution Overview
The page renders three `TrackUploadStatus` values uniformly. Two already-derived
(`trackUploadStatus`, and a new metadata-only marks status); the third (photos) is a new
frame-granular fold. `MainActivity` owns all three derivations (consistent with it owning all
overlay state) and passes them to a stateless `UploadScreen`. Per-section visibility gates on
`total > 0`; the «Финиш» line per section gates on `local.outcome != null || local.uploaded > 0`.
A single page-level 30s ticker feeds `nowMs` to all sections.

**Design decisions & rationale:**
- *Reuse `TrackUploadStatus`/`TargetLine`* — shared **data**, not shared UI. To avoid an
  awkward `ui.upload → ui.track` dependency, **move** these two data classes to a neutral home
  and re-point imports (see Task 1). `ReceiptLine`/`outcomeLabelRu` become a single canonical
  copy in `ui/upload/` (net-removes the current two-file duplication; still "duplicate, don't
  couple" relative to any future third consumer).
- *Photos count frames* — total = Σ frame-count over marks with photos; a target's numerator
  sums frames only for marks whose `photosUploadedX = 1`. A mid-drain mark (some frames sent,
  flag not yet flipped) contributes **0** until all its frames land: mark-granular tick,
  frame-granular denominator. This matches what the DB can truthfully report and is documented
  in the fold.
- *Marks metadata-only counter* — drops the frame condition from `uploadCounts` so the Отметки
  section answers "did the take row reach the server?" independent of its photos (which the Фото
  section covers).

## Technical Details
- **`MarkDao.uploadCountsMetadata(teamId, raceId): Flow<UploadCounts>`** — same shape as
  `uploadCounts` but the `CASE` conditions are bare `uploadedLocal` / `uploadedCloud` (no
  `photoPath`/`photosUploadedX` term).
- **`MarkDao.photoFrameRows(teamId, raceId): Flow<List<PhotoFrameRow>>`** — returns
  `(photoPath, photosUploadedLocal, photosUploadedCloud)` for in-scope rows where
  `photoPath IS NOT NULL`. `PhotoFrameRow` is a small `data class` (new, in `data/db/`).
- **`foldPhotoFrameCounts(rows: List<PhotoFrameRow>): UploadCounts`** — pure:
  `total = Σ photoPaths(r.photoPath).size`; `local = Σ size where r.photosUploadedLocal`;
  `cloud = Σ size where r.photosUploadedCloud`. Lives in `MarkRepository` (or a pure helper file)
  and is unit-tested.
- **`showFinishLine(line: TargetLine): Boolean = line.outcome != null || line.uploaded > 0`** —
  pure, in `ui/upload/UploadScreen.kt`'s companion or a pure helper; unit-tested.
- **`MarkRepository`**: add `uploadCountsMetadata(...)` (delegates to DAO) and
  `photoFrameCounts(teamId, raceId): Flow<UploadCounts>` = `photoFrameRows(...).map(::foldPhotoFrameCounts)`.

## What Goes Where
- **Implementation Steps** (`[ ]`): all code, tests, and the CLAUDE.md doc update.
- **Post-Completion** (no checkboxes): on-device manual smoke test of the page + `connectedDebugAndroidTest`
  on an emulator (instrumented run needs hardware, so it is flagged for the human runner).

## Implementation Steps

### Task 1: Relocate `TargetLine`/`TrackUploadStatus` view-models to a neutral home

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/upload/UploadStatusModels.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] move `data class TargetLine` and `data class TrackUploadStatus` (with `fullyUploaded`) verbatim from `TrackCard.kt` into new `ui/upload/UploadStatusModels.kt` (package `ru.kolco24.kolco24.ui.upload`)
- [x] `TrackCard.kt` still uses both types (param line 118, render sites) through Task 6 — add `import ru.kolco24.kolco24.ui.upload.TargetLine`/`TrackUploadStatus` now that they left its own file
- [x] update the `ui.track.TrackUploadStatus` import in `TeamScreen.kt` (line 54) → `ui.upload.TrackUploadStatus` (it declares a `trackUploadStatus: TrackUploadStatus?` param, line 99)
- [x] update imports in `MainActivity.kt` (`ui.track.TargetLine`/`TrackUploadStatus` → `ui.upload.…`) and `MarksScreen.kt` (line 100-101)
- [x] confirm no other references (`grep -rn "ui.track.TargetLine\|ui.track.TrackUploadStatus" app/src` returns nothing)
- [x] build compiles: `./gradlew compileDebugKotlin` (pure move, no behavior change — no new test needed; note in commit)

### Task 2: Add metadata-only + photo-frame DAO queries and `PhotoFrameRow`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/PhotoFrameRow.kt` (or co-locate in MarkDao.kt)

- [x] add `data class PhotoFrameRow(val photoPath: String?, val photosUploadedLocal: Boolean, val photosUploadedCloud: Boolean)`
- [x] add `uploadCountsMetadata(teamId, raceId): Flow<UploadCounts>` — copy of `uploadCounts` with `CASE WHEN uploadedLocal …` / `CASE WHEN uploadedCloud …` (drop the `photoPath`/`photosUploadedX` term); alias `total`/`local`/`cloud`
- [x] add `photoFrameRows(teamId, raceId): Flow<List<PhotoFrameRow>>` — `SELECT photoPath, photosUploadedLocal, photosUploadedCloud FROM marks WHERE teamId = :teamId AND raceId = :raceId AND photoPath IS NOT NULL`
- [x] add the two queries to instrumented `MarkDaoTest` (metadata-only counts ignore pending frames; `photoFrameRows` returns only photo rows with the right flags)
- [x] run `./gradlew compileDebugKotlin` and confirm Room codegen is happy (instrumented test run deferred to Task 6 / Post-Completion since it needs a device)

### Task 3: Add repository counters and the pure photo-frame fold

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/PhotoFrameCountsTest.kt`

- [x] add pure `foldPhotoFrameCounts(rows: List<PhotoFrameRow>): UploadCounts` using `photoPaths(...)` for per-row frame count; document the mark-granular-tick / frame-granular-denominator behavior in a KDoc comment
- [x] add `MarkRepository.uploadCountsMetadata(teamId, raceId)` delegating to the DAO
- [x] add `MarkRepository.photoFrameCounts(teamId, raceId): Flow<UploadCounts>` = `markDao.photoFrameRows(...).map(::foldPhotoFrameCounts)`
- [x] write unit tests for `foldPhotoFrameCounts`: empty list → 0/0/0; a non-null empty encoded list (`"[]"`) → 0 frames (row present, no frames); single mark 3 frames both flags set → 3/3/3; mid-drain mark (flags 0) contributes 0 to numerators but frames to total; mixed (one local-done, one cloud-done) → asymmetric local/cloud
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 4: Build `UploadScreen` overlay with three sections, «Финиш» gating, empty state

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/upload/UploadScreen.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/upload/FinishLineVisibilityTest.kt`

- [x] pure `showFinishLine(line: TargetLine): Boolean = line.outcome != null || line.uploaded > 0`
- [x] `UploadScreen(marks: TrackUploadStatus?, photos: TrackUploadStatus?, track: TrackUploadStatus?, onBack: () -> Unit)` — `TopAppBar` «Загрузка данных» + back arrow (mirror `SettingsScreen`), scrollable `Column`
- [x] one page-level 30s ticker `produceState` → `nowMs`, threaded to all sections
- [x] `UploadSection(title, status, nowMs)` card (`surfaceContainerLow`, large shape, `MarksUploadPanel` inset vocabulary): header + always-shown «Интернет» `ReceiptLine` + conditional «Финиш» `ReceiptLine` gated on `showFinishLine(status.local)`; render the section only when `status != null && status.total > 0`
- [x] canonical `ReceiptLine` + `outcomeLabelRu` copied here (single home); order sections Отметки → Фото → GPS-трек
- [x] empty state (all three null/`total==0`): centered cloud glyph + «Пока нечего загружать» + subtitle naming отметки/фото/GPS-трек
- [x] write unit tests for `showFinishLine` (no outcome + 0 uploaded → false; outcome present → true; uploaded>0 no outcome → true)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 5: Wire the overlay + entry row + derivations in MainActivity and TeamScreen

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] `TeamScreen`: add `onOpenUpload: () -> Unit` param; import `Icons.Outlined.CloudUpload`; in `MiscSection` set the «Настройки» row `isLast = false` and add a sibling `MiscRow(icon = CloudUpload, label = "Загрузка данных", subtitle = "Отметки, фото, трек", isLast = true, onClick = onOpenUpload)`; thread `onOpenUpload` through `MiscSection(onOpenSettings, onOpenUpload)` and both call sites (footer + inline, lines ~108/166)
- [x] `MainActivity`: add `var showUpload by rememberSaveable { mutableStateOf(false) }`; pass `onOpenUpload = { showUpload = true }` to the `TeamScreen(...)` call
- [x] `MainActivity`: derive `marksMetadataUploadStatus` (scoped `produceState` over `markRepo.uploadCountsMetadata`, same scoped-pair guard as existing) and `photoUploadStatus` (over `markRepo.photoFrameCounts`), both joining `markUploadOutcomes` (line 673, `container.markUploadOutcomes.collectAsState()`) for the scope; keep existing `trackUploadStatus`
- [x] `MainActivity`: render the `UploadScreen` overlay after `Scaffold` alongside settings — `BackHandler(enabled = showUpload && !showScan && teamFlowStep == None && confirmTeamId == null)` sets `showUpload = false`; `if (showUpload && …) UploadScreen(marks = marksMetadataUploadStatus, photos = photoUploadStatus, track = trackUploadStatus, onBack = { showUpload = false })`
- [x] `MainActivity`: `showUpload` mirrors `showSettings` — add it to the deeper-overlay busy guard (line ~966) and reset it in the two places `showSettings` is reset: the captured-scan/`onScanClick` block (line ~981) and `closeScanOverlay` (line ~1165). It is **not** reset in `LaunchedEffect(selectedTeamId)` (line 758) — same as `showSettings`, since a team switch is only reachable via Settings, not while this overlay is up
- [x] build: `./gradlew compileDebugKotlin` (Compose wiring untested by convention; covered by manual smoke in Post-Completion)

### Task 6: Remove the old in-place upload indicators

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] `TrackCard.kt`: delete `UploadStatusRow`, `CloudReceiptPill`, `UploadReceiptCard`, `ReceiptLine`, `outcomeLabelRu`; remove the `uploadStatus: TrackUploadStatus?` param and both `if (upload != null) UploadStatusRow(...)` render sites + the `val upload = …` line; drop now-unused imports (Popup, produceState, delay, the `ui.upload.*` import added in Task 1, etc.)
- [x] `TeamScreen.kt`: at the `TrackCard(...)` call (line ~162) drop the `uploadStatus = trackUploadStatus` argument; delete the now-dead `trackUploadStatus: TrackUploadStatus? = null` param (line 99) and the `ui.upload.TrackUploadStatus` import (was line 54)
- [x] `MarksScreen.kt`: delete `MarksUploadPanel`, its `ReceiptLine`/`outcomeLabelRu` copy, the `uploadStatus` param, and the `item("upload_status")` block; drop now-unused imports (`TargetLine`, `TrackUploadStatus`, `relativeTimeRu`, `produceState`, `delay`)
- [x] `MainActivity`: remove `uploadStatus = marksUploadStatus` from the `MarksScreen(...)` call and `trackUploadStatus = trackUploadStatus` from the `TeamScreen(...)` call; delete **only** the orphaned `scopedMarkUploadCounts` (lines ~664-672) and `marksUploadStatus` (lines ~674-687) derivations — **keep `markUploadOutcomes` (line 673)**, which the new Task-5 derivations depend on; keep `trackUploadStatus` (now consumed by `UploadScreen`)
- [x] `grep -rn "MarksUploadPanel\|UploadStatusRow\|CloudReceiptPill" app/src` returns nothing
- [x] run `./gradlew testDebugUnitTest lintDebug` — must pass (`MarksMappingTest`/`TileFillTest` still green after MarksScreen edit)

### Task 7: Verify acceptance criteria
- [x] all three sections reachable from Команда → Прочее → «Загрузка данных»; back closes the overlay (verified by code inspection: `MiscRow(onClick = onOpenUpload)` in `TeamScreen.kt` sets `showUpload = true` in `MainActivity.kt`; `BackHandler(enabled = showUpload && …) { showUpload = false }` — full manual on-device confirmation remains in Post-Completion)
- [x] a section hides when its total is 0; «Финиш» hides until it reports; empty state shows when nothing recorded (verified in `UploadScreen.kt`: `UploadSection` returns early on `status.total <= 0`, `showFinishLine` gates the «Финиш» line and is unit-tested by `FinishLineVisibilityTest`, `hasAny` drives the empty state)
- [x] no upload UI remains on the Отметки or Команда tabs (`grep -rn "MarksUploadPanel\|UploadStatusRow\|CloudReceiptPill" app/src` returns nothing, confirmed in Task 6)
- [x] run full unit suite: `./gradlew testDebugUnitTest` — passes
- [x] run `./gradlew lintDebug` — passes

### Task 8: Update documentation and file the plan
- [x] update `CLAUDE.md`: add `ui/upload/UploadScreen.kt` + `UploadStatusModels.kt` bullets; note the new `MarkDao.uploadCountsMetadata`/`photoFrameRows` + `foldPhotoFrameCounts`; update the `TrackCard`/`MarksScreen` bullets to drop the removed upload rows; add `showUpload` to the overlay-stack list; update the instrumented-test note to include the new MarkDao queries
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual / device-bound — no checkboxes.*

**Manual verification:**
- On an emulator/device: record a few NFC takes, a photo mark, and a short GPS track; open
  «Загрузка данных» and confirm all three sections populate, counts tick to done, and «Финиш»
  appears only once the LAN target reports (needs a reachable LAN server, else stays hidden).
- Rotation + process-death: `showUpload` survives via `rememberSaveable`; overlay re-renders.

**Instrumented tests (need hardware):**
- `./gradlew connectedDebugAndroidTest` to run the extended `MarkDaoTest`
  (`uploadCountsMetadata` + `photoFrameRows`) on an emulator/device.
