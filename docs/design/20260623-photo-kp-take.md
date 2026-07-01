# Взятие КП по фото (команда фотографируется на КП)

## Overview
Add a second way to take a checkpoint (взятие КП): the team arrives at a КП and **photographs
itself** as proof, in parallel to the existing NFC-chip flow. The photo is captured **in-app with
CameraX**, stored locally, and written as a `MarkEntity` with `method = "photo"`.

Why photo takes exist (settled in brainstorm):
- **Fallback** for a КП whose chip is lost/broken — the team can still take it.
- **Universal extra take + anti-cheat + «на память»**: a photo of the whole team at the marker
  proves everyone physically arrived (not just whoever carries the chip), and is a keepsake.

Key decisions (settled in brainstorm):
- **Camera-first flow** (Option A): tap «Фото» → camera opens → snap → confirm-КП sheet → save.
- **Photo is the sole proof** — no per-member bracelet scans. `present` is left empty; the mark is
  `complete = true` immediately (the photo *is* the completion), so it counts toward «ВЗЯТО»/«СУММА»
  and highlights the КП in the legend, offline, the same instant.
- **No double-count**: `totalScore`/`takenPoints` already dedup by **distinct point**, so a photo of
  a КП already taken by NFC adds the photo tile «на память» without adding points again. No new
  scoring logic needed.
- **КП identification**: manual picker / type-the-number over the synced legend, **pre-filled** when
  a `complete` NFC take happened in the **last 3 minutes** (auto-link to that КП).
- **In-app CameraX** (not the system-camera Intent) — full control over UX, single visual style.
- Server upload of photos is **out of scope** (the `uploadedLocal/uploadedCloud` seeds already exist;
  no upload path exists for *any* mark yet).

## Context (from discovery)
- **Scaffolding already present** (no schema migration needed):
  - `data/db/MarkEntity.kt` — already has `method: String`, `photoPath: String? = null`,
    `uploadedLocal/uploadedCloud`. The marks table is **v6** and unchanged by this feature.
  - `ui/marks/MarksScreen.kt` — `enum MarkKind { NFC, PHOTO }`; `marksToTiles` already maps
    `method == "photo" → PHOTO` (line ~101, covered by `MarksMappingTest`); `PhotoTileBody`
    (lines ~446) already renders the photo-style tile (charcoal seat + `MiniCpBadge`). **The «Фото»
    `OutlinedButton` exists at line ~189–203 with an empty `onClick = {}`** — this is the entry point
    to wire up.
  - `data/MarkRepository.kt` — `startKpTake(...)` hardcodes `method = "nfc"`; pure metric helpers
    `takenPointCount` / `takenPoints` / `totalScore` all filter on `complete` and dedup by `point`.
- **MainActivity wiring to mirror** (the scan overlay is the template):
  - `var showScan by rememberSaveable` (line ~399); `MarksScreen(... onScanClick = {...})`
    (lines ~689–710); the overlay render + `BackHandler(enabled = showScan)` (lines ~745–746);
    overlay resets duplicated in `onScanClick` and on team switch `LaunchedEffect(selectedTeamId)`.
  - `val marks by remember(selectedTeamId) { markRepo.observeMarks(it) ... }` (line ~502) →
    `safeMarks` feeds `MarksScreen`; this same list yields the recent-NFC auto-link candidate.
  - `safeCheckpoints` (the synced legend) + `checkpointCosts`/`checkpointColors` maps (lines ~537–542)
    supply the КП picker rows (number + cost + color).
  - The NFC dispatcher `busy` guard (`LaunchedEffect(captured, teamState)`, line ~576) must learn
    about the new overlay so a stray chip tap doesn't open «Отметить КП» on top of the photo flow.
- **No camera / file-capture / image-loader code exists anywhere** — CameraX, the `CAMERA`
  permission, and local JPEG storage are all new.
- **Gradle**: version-catalog style (`gradle/libs.versions.toml` + `app/build.gradle.kts`). CameraX
  artifacts are new entries. No FileProvider needed (CameraX writes to our own file; FileProvider is
  only for the system-camera Intent we are **not** using).

## Decisions recap (brainstorm answers, for the implementer)
| Question | Decision |
|---|---|
| Role of photo | Fallback for chip-less КП **and** universal extra take (memory + anti-cheat) |
| КП identity | Manual picker / type number; auto-link to a `complete` NFC take from the last 3 min |
| Member presence | Photo is the sole proof — no bracelet scans, `present` empty |
| Scoring | `complete = true` immediately; distinct-point dedup means no double-count vs NFC |
| Camera | In-app CameraX |
| Flow shape | Camera-first (snap, then confirm КП) |

## Implementation steps

### 1. Dependencies & permissions
- `gradle/libs.versions.toml`: add a `camerax = "1.4.2"` version and library entries
  `androidx-camera-core`, `androidx-camera-camera2`, `androidx-camera-lifecycle`,
  `androidx-camera-view` (group `androidx.camera`).
- `app/build.gradle.kts`: add the four `implementation(libs.androidx.camera.*)` lines.
- `app/src/main/AndroidManifest.xml`: add
  `<uses-permission android:name="android.permission.CAMERA" />` and
  `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`
  (`required="false"` keeps the app installable on camera-less devices, mirroring the NFC
  `required="false"` pattern — a missing camera gracefully disables the «Фото» affordance).

### 2. Photo storage helper — `data/photo/PhotoStore.kt` (new)
- A thin wrapper around app-private storage: `fun newPhotoFile(context): File` →
  `File(context.filesDir, "photos/<uuid>.jpg")` (creates the `photos/` dir). CameraX
  `ImageCapture.OutputFileOptions` targets this file; `photoPath` stores its absolute path.
- Keep it tiny and Android-only (no unit test — trivial I/O, repo convention).

### 3. `MarkRepository` — photo take + auto-link helper
- Add `suspend fun addPhotoTake(raceId, teamId, point, number, cost, photoPath: String, expectedCount, now): String`:
  generate a UUID, upsert a `MarkEntity(method = "photo", cpUid = "", cpCode = "", present = emptyList(),
  complete = true, photoPath = photoPath, ...)`, return the id. `complete = true` is set
  **unconditionally** — the photo is the completion (do not derive it from `present`).
- Add a **pure** top-level helper
  `fun recentNfcPoint(marks: List<MarkEntity>, now: Long, windowMs: Long = 180_000L): Int?` —
  the `point` of the most recent `complete` mark with `method == "nfc"` whose `takenAt` is within
  `windowMs` of `now`, else `null`. This drives the КП auto-link. Keep `windowMs` a parameter for
  testability.
- Unit-test in `MarkRepositoryTest` (fake `MarkDao`): `addPhotoTake` writes a complete photo row;
  a photo of an already-NFC-taken point does **not** change `totalScore` (distinct-point dedup);
  `recentNfcPoint` returns the in-window NFC point, `null` when stale/absent/photo-only.

### 4. Photo overlay — `ui/photo/PhotoScreen.kt` (new, full-screen overlay)
Mirror the `ScanScreen` overlay pattern (full-screen `Box`, tap-swallowing, own state). Signature
roughly:
```
fun PhotoScreen(
    checkpoints: List<CheckpointEntity>,
    checkpointColors: Map<Int, String>,
    prefilledPoint: Int?,               // from recentNfcPoint, may be null
    onSave: suspend (point: Int, photoPath: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
)
```
Internal states (a small `sealed interface PhotoStep`):
1. **PermissionGate** — if `CAMERA` not granted, request via
   `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`; on permanent
   denial show a rationale + `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` deep-link (mirrors the
   NFC-disabled prompt in `BindChipSheet`).
2. **Camera** — CameraX `PreviewView` inside `AndroidView`, bound to the lifecycle
   (`ProcessCameraProvider` + `Preview` + `ImageCapture`); a large shutter button calls
   `imageCapture.takePicture(outputFileOptions, executor, callback)` writing to a `PhotoStore` file
   off the main thread.
3. **Confirm** — show the captured photo thumbnail + a **КП selector**: a `LazyColumn` of
   `checkpoints` (mono `<cost>-<number>` label + color dot, reusing `parseCheckpointColor`) with a
   number-filter `TextField` at the top, **pre-scrolled/pre-selected to `prefilledPoint`** when
   non-null. A «Сохранить» CTA (enabled once a КП is selected) calls
   `onSave(point, photoPath)` then `onClose()`. A «Переснять» action returns to step 2; closing
   before save deletes the orphan file.
- The pure КП-filter (number substring → list) can be a small top-level `fun` unit-tested if it
  grows; otherwise keep inline. Camera/permission/Compose host is **untested** (repo convention).
- Pre-pick the **front/rear** camera: default rear (`CameraSelector.DEFAULT_BACK_CAMERA`); a flip
  button is a nice-to-have, optional.

### 5. MainActivity wiring
- Add `var showPhoto by rememberSaveable { mutableStateOf(false) }`.
- Wire `MarksScreen`'s «Фото» button: thread a new `onPhotoClick: () -> Unit` param into
  `MarksScreen` (replacing the empty `onClick = {}` at line ~190) and pass it from the call site.
  Gate it exactly like `onScanClick`: open only when `teamForTab != null`, else route to the comp
  picker. Opening resets the other overlays (same reset block as `onScanClick`) and sets
  `showPhoto = true`.
- Render `PhotoScreen` after the `ScanScreen` block in the same `Box`, gated on
  `showPhoto && !showScan`. Pass `checkpoints = safeCheckpoints`,
  `checkpointColors = checkpointColors`,
  `prefilledPoint = recentNfcPoint(safeMarks, System.currentTimeMillis())`, and
  `onSave = { point, path -> container.applicationScope.launch { markRepo.addPhotoTake(...) }.join()-style }`
  (run on `applicationScope` so the write outlives the closing overlay, mirroring `startKpTake`).
  Resolve `number`/`cost` for the chosen point from `safeCheckpoints`; `expectedCount = scanRoster.size`.
- `BackHandler(enabled = showPhoto && !showScan && teamFlowStep == None && confirmTeamId == null)` →
  `showPhoto = false` (registered alongside the other overlay back-handlers).
- Add `showPhoto` to the overlay **resets** in `onScanClick` and the team-switch
  `LaunchedEffect(selectedTeamId)`, and add it to the NFC dispatcher **`busy`** set
  (`LaunchedEffect(captured, teamState)`, line ~576) so a КП chip tapped while the photo overlay is
  up is dropped instead of opening «Отметить КП» behind it.

### 6. (No DB migration) — confirm marks stays v6
- `MarkEntity` already carries `method` + `photoPath`; **no** `AppDatabase` version bump, **no**
  new migration, **no** schema JSON. Call this out explicitly so the implementer doesn't add one.

## Testing
- `./gradlew testDebugUnitTest`:
  - `MarkRepositoryTest` — `addPhotoTake` (complete photo row, empty `present`, `method=="photo"`),
    distinct-point dedup (`totalScore` unchanged by a photo of an NFC-taken point),
    `recentNfcPoint` (in-window / stale / absent / photo-only-ignored).
  - `MarksMappingTest` already covers `PHOTO` tile mapping — extend if tile content changes.
- `./gradlew lintDebug` must pass (new manifest permission, CameraX APIs — watch `NewApi` on
  minSdk 24; CameraX 1.4 supports API 21+).
- Manual: photograph a team → confirm auto-linked КП after a fresh NFC take; confirm manual pick
  when no recent NFC; verify the КП lights up in «Легенда» and «ВЗЯТО»/«СУММА» update; verify a
  photo of an already-taken КП does not double the score; verify camera-permission denial path.
- The Compose hosts (`PhotoScreen`, camera) are untested per repo convention.

## Out of scope (YAGNI — note as follow-ups)
- **Photo upload to server** (local/cloud) — no upload path exists for any mark yet; the
  `uploadedLocal/uploadedCloud` seeds stay `false`.
- **Viewing/gallery** of saved photos (tap a photo tile to open full-screen) — keepsake viewing is a
  later add; this plan only *captures and stores* the photo. `PhotoTileBody` keeps its current
  badge-placeholder design (no thumbnail decode → no Coil/image-loader dependency now).
- **Photo cleanup/retention** policy (deleting files for deleted marks) — defer until upload lands.
- **Per-member presence in photo mode** — explicitly rejected; photo is the sole proof.

## Risks / watch-outs
- **CameraX + minSdk 24**: pin a 1.4.x line (API 21+). Verify `lintDebug` raises no `NewApi`.
- **`complete = true` with empty `present`**: deliberate. Double-check `marksToTiles` (filters
  `complete`) and the metric helpers treat the row correctly — they key on `point`/`complete`, not
  `present`, so a photo row tiles and scores with an empty roster set.
- **Auto-link staleness**: `recentNfcPoint` is computed at overlay-open from `System.currentTimeMillis()`;
  a long camera session won't re-evaluate it (acceptable — the user can still re-pick the КП in the
  confirm step).
- **Orphan files**: closing/«Переснять» before save must delete the just-captured file so storage
  doesn't leak.
- **Overlay precedence**: ensure `showPhoto` joins every place `showScan` already appears in the
  reset/guard/busy logic, or the photo overlay and scan overlay can race.
