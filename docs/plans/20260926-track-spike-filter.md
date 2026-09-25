# Track spike filter + «Все точки» toggle

## Overview
- Replace the read-time accuracy cutoff `filterPoints` (drops every fix with `accuracy > 50 m`) with a smart spike filter tuned for a foot/bike rogaine.
- Problem: the 50 m cutoff throws away usable 60–100 m GPS fixes, so the map and GPX show long straight lines. The «Точек» counter shows the raw count (e.g. 96) while GPX has far fewer (e.g. 26), which confuses users.
- The new filter keeps noisy-but-plausible fixes and removes short runs of points that break the track: isolated spikes (including multipath fixes with a good reported accuracy), a cluster of network fixes at recording start (indoors, before GPS lock), trailing network fixes, and anything worse than 500 m. Where a jump can't be explained away, the line is broken instead of drawing the jump.
- The filter returns **lines** (`List<List<T>>`), not a flat list: every transition inside a line is reachable, and nothing is drawn between lines. Map and GPX draw lines, not raw segments.
- The map draws each line as a separate part of a `MultiLineString` (GPX: one `<trkseg>` per line). Today one `LineString` joins recording segments, so a stop→start gap draws a straight line that no filter can remove.
- A user toggle «Все точки» disables filtering. It lives in Settings and as a chip on the map; both write the same persisted preference.
- Server upload stays raw (unchanged). The iOS port is out of scope and not tracked here.

## Context (from discovery)
- `data/track/TrackModels.kt` — `TrackPointLike` (lines 45–54), `DEFAULT_MAX_ACCURACY_METERS`, `filterPoints`, `trackPointTimeMs`, `sortedTrackPoints`.
- `data/db/TrackPointEntity.kt:45` — implements `TrackPointLike`, already has `segmentId: String` (becomes `override`).
- `ui/map/MapLogicTest.kt:51-59` — test fake `Pt` implementing `TrackPointLike`. Only other implementor.
- `ui/map/MapLogic.kt:57-74` — `trackGeoJson` emits one `LineString`; used by `TrackMapView.kt:123-124` (off-main). `dataBounds(track, pins)` (`MapLogic.kt:114`) used by `TrackMapView.kt:237` area for the camera. Tests: `MapLogicTest.kt:144-165`.
- `ui/map/MapScreen.kt:66` / `TrackMapView.kt:106` — `track: List<TrackPointLike>` params.
- `data/track/GpxExport.kt` — `buildGpx(points, name)` groups consecutive `segmentId` runs into `<trkseg>`; tests in `GpxExportTest.kt` (lines 50–75 cover segment grouping).
- `MainActivity.kt:128` (import), `:915` — `trackUsable = sortedTrackPoints(filterPoints(safeTrack))`, feeds map (`MapScreen(track = trackUsable)`, line 1676) and time span (lines 916–917).
- `MainActivity.kt:1183` — `onShareTrack` runs `filterPoints` then `sortedTrackPoints` for GPX.
- `MainActivity.kt:389-399` — preference pattern: `container.trackProfilePreference.profile.collectAsState()` at Activity level, value + setter passed into `Kolco24AppRoot`.
- `MainActivity.kt:1705` — TeamScreen `trackPointCount = safeTrack.size`; `:1911` — Settings `trackPointCount` (stays raw, used by «Очистить трек»).
- `data/TrackProfilePreference.kt` + `TrackProfilePreferenceTest.kt` — lambdas store + `StateFlow`, `fromSharedPreferences`, prefs file `kolco24.settings`.
- `AppContainer.kt:482` — `trackProfilePreference by lazy`.
- `ui/settings/SettingsScreen.kt:174,472` — `EconomyModeRow` is the template for the new row.
- `ui/map/MapScreen.kt:105-110` — `NoMapBanner` at `TopCenter`; bottom `Column` holds pin/download cards + OSM attribution.
- `ui/track/TrackCard.kt:97,171-181` — `RecordingHeader(pointCount)` (while recording); `:213-221` — metrics row (`Metric(label = pointsWord(...), value = pointCount)`).
- `test/.../data/track/TrackPointMappingTest.kt:121-141` — existing `filterPoints` tests (to be replaced).
- Stale `filterPoints` references: `TrackModels.kt:6-10` (file header), `:43` (`TrackPointLike` KDoc), `GpxExport.kt:10`, `MainActivity.kt:914,919-920` (comments), `docs/design/DATA-NOTES.md:29`.
- No haversine helper exists yet.

## Development Approach
- **testing approach**: TDD — write `trackLines` / preference tests first, then the code.
- complete each task fully before moving to the next; every task leaves the build compiling
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task (Compose UI and trivial wiring are untested by project convention — those tasks rely on build + lint)
- **CRITICAL: all tests must pass before starting next task**
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./gradlew testDebugUnitTest` after each change

## Testing Strategy
- **unit tests**: pure `trackLines` + `haversineMeters` in `TrackModels`; line-based `trackGeoJson` in `MapLogic` and `buildGpx`; `TrackFilterPreference` reactive behaviour (JVM, lambdas store).
- **UI**: Compose screens are untested by convention; verified via `./gradlew lintDebug` + manual check.
- no e2e suite in the project.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- keep plan in sync with actual work done

## Solution Overview
- `trackLines(points, filter)` is a pure function over already-sorted points. It returns lines (`List<List<T>>`).
- Accuracy is **not** a judge of which point is right (it is a 68% estimate, not a guarantee). It is used only for the 500 m cap, as a noise allowance in reachability, and for one tail rule where the gap is large (≥ 3×).
- Inside each `segmentId` run the points are cut into **chains** at every unreachable step. Short chains are *candidates* for removal, not garbage: an interior short chain is removed only if bypassing it gives a reachable connection. Otherwise it stays as its own line with gaps around it.
- Head and tail get separate, more careful rules (no bypass possible there). The live tail is shown by default, so the track never "freezes" after a break.
- A segment made only of short chains is not wiped because of size alone.
- One left-to-right pass, deterministic. Rare miss accepted: two independent spike chains in a row, mutually unreachable — one stays as a separate line, but no jump is drawn.
- One preference, two switches (Settings row + map chip). The host collects it once and passes value + setter down, like `trackProfile`.

## Technical Details
Constants (in `TrackModels.kt`, replacing `DEFAULT_MAX_ACCURACY_METERS`):
- `HARD_CAP_ACCURACY_M = 500f`
- `MAX_SPEED_MPS = 14f` (~50 km/h; covers bike downhill)
- `SHORT_CHAIN_MAX_POINTS = 3`, `SHORT_CHAIN_MAX_DURATION_MS = 60_000L`
- `TAIL_ACCURACY_RATIO = 3f`

`TrackPointLike` gains `val segmentId: String`.

`fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double` — pure, Earth radius 6_371_000 m.

`reachable(a, b)`: `d = haversine`, `dt = max(|t(b) − t(a)|, 1000 ms)` with `t = trackPointTimeMs`; reachable iff `max(0, d − min(acc(a), acc(b))) / (dt / 1000) ≤ MAX_SPEED_MPS`.

`short(chain)`: `size ≤ SHORT_CHAIN_MAX_POINTS` **and** `t(last) − t(first) < SHORT_CHAIN_MAX_DURATION_MS`. A single point is always short (duration 0), even in Economy. At 15 s spacing the point count decides (4 points = 45 s is not short).

`fun <T : TrackPointLike> trackLines(points: List<T>, filter: Boolean): List<List<T>>` — input already sorted via `sortedTrackPoints`:
- `filter = false` («Все точки»): split into consecutive `segmentId` runs, nothing else (no cap).
- `filter = true`:
  1. Drop `accuracy > HARD_CAP_ACCURACY_M`.
  2. Split into consecutive `segmentId` runs. Each run is processed alone; lines never cross runs.
  3. Cut the run into chains `c0..ck-1` at every `!reachable(p[i], p[i+1])`.
  4. `k == 1` → the chain is one line, kept as is.
  5. Walk chains left to right, building lines. For chain `ci`:
     - **Head** (`i == 0`): drop if `short(c0) && !short(c1)` (a long chain proves the real track is elsewhere).
     - **Interior** (`0 < i < k−1`) and `short(ci)`: drop if `reachable(lastAccepted, c(i+1).first)`, where `lastAccepted` is the last point of the current line.
     - **Tail** (`i == k−1`) and `short(ci)`: drop only if the previous chain `c(i−1)` is long and was kept **and** `median(acc(ci)) ≥ TAIL_ACCURACY_RATIO × median(acc(c(i−1)))`. Otherwise keep (live tail stays visible; when the next fix arrives it becomes interior and gets the bypass check).
     - A kept chain joins the current line if `reachable(current.last, chain.first)`; otherwise the current line closes and the chain starts a new line.
  6. Return all lines of all runs, in order. Lines are never empty.

Worked examples (pinned in tests):
- `[A,B] [X] [C,D]`, X a spike, B→C reachable → X dropped, one line `[A,B,C,D]`.
- A (0 s, 0 m, 5 m), B (15 s, 200 m, 20 m), C (30 s, 500 m, 10 m) → chains `[A,B]`, `[C]`; C is a short tail after a short chain → kept; lines `[A,B]`, `[C]`. No good point is lost.
- Start: 3 network fixes (~300 m acc) near a tower, then a long GPS chain → head dropped.
- Multipath spike with 8 m accuracy between long 15 m chains, bypass reachable → dropped (accuracy doesn't save it).
- Interior short chain whose bypass is unreachable → kept as its own line, gaps on both sides.
- Economy (180 s spacing): allowance ~2.5 km per step, chains rarely break; filter rarely triggers. A spike can't be told from walking at that spacing.
- Driving above ~50 km/h: the track breaks into chains; short ones may drop, long ones stay as separate lines.

Consumers:
- Map: `trackGeoJson(lines)` → one `MultiLineString` feature, one part per line with ≥ 2 points (a 1-point line is not drawable and is skipped); empty collection if no part qualifies.
- GPX: `buildGpx(lines, name)` → one `<trkseg>` per line; no own `segmentId` grouping any more.
- Time span, counts: `lines.flatten()`.

Preference: `TrackFilterPreference(load: () -> Boolean, save: (Boolean) -> Unit)` with `showAllPoints: StateFlow<Boolean>` and `setShowAllPoints(Boolean)`; adapter uses `getBoolean("track_show_all_points", false)` in `kolco24.settings`. Setter is synchronous (`apply()`), same as `setProfile`.

Integration in `MainActivity`:
- `trackLinesNow = remember(safeTrack, showAllPoints) { trackLines(sortedTrackPoints(safeTrack), filter = !showAllPoints) }`; `trackUsable = trackLinesNow.flatten()` for the time span.
- `hiddenCount = safeTrack.size - trackUsable.size` (always 0 when `showAllPoints` is on; the selected chip then shows no count — intended).
- `onShareTrack` uses the same expression; the empty-result toast becomes «Нет точек для экспорта».

## What Goes Where
- **Implementation Steps**: code, tests, docs in this repo.
- **Post-Completion**: manual field check.

## Implementation Steps

### Task 1: Pure `trackLines` + `haversineMeters`, migrate call sites

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/TrackPointEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/GpxExport.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackLinesTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackPointMappingTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/map/MapLogicTest.kt`

- [x] add `segmentId` to `TrackPointLike`; make `TrackPointEntity.segmentId` `override`; add `segmentId` to the `MapLogicTest` fake
- [x] write `haversineMeters` tests (known distance, zero distance)
- [x] write `trackLines` tests (filter on): `[A,B][X][C,D]` → one line of 4; the A/B/C example → lines `[A,B]`,`[C]`; realistic spike (acc 450 m, displaced 600 m, dt 15 s) between long chains removed; multipath spike with better accuracy removed; slow 60–100 m jitter kept as one line; head network cluster before a long chain removed; short head before a short chain kept; trailing network fixes (≥ 3× worse median accuracy) after a long chain removed; trailing GPS-quality short chain after a break kept as its own line; interior short chain with unreachable bypass kept as its own line; two long chains with an unreachable step → two lines; segment of only short chains → no points removed; different `segmentId`s → separate lines, never merged; `accuracy > 500` dropped, `= 500` kept; `dt = 0` (1 s floor); `short()` boundaries (1 point short; 3 points over ≥ 60 s not short; 4 points in 45 s not short); empty input → empty list; generic type preserved
- [x] write `trackLines` tests (filter off): only split by consecutive `segmentId`, 500 m cap not applied
- [x] implement `haversineMeters`, constants, `trackLines` in `TrackModels.kt`
- [x] migrate `MainActivity.kt:915` and `:1183` to `trackLines(sortedTrackPoints(...), filter = true).flatten()` for now (consumers switch to lines in Task 2); drop the `filterPoints` import
- [x] remove `filterPoints` + `DEFAULT_MAX_ACCURACY_METERS` and their tests in `TrackPointMappingTest`
- [x] fix stale references: `TrackModels.kt` header + `TrackPointLike` KDoc, `GpxExport.kt:10`, `MainActivity.kt:914,919-920` comments
- [x] run `./gradlew testDebugUnitTest` — must pass

### Task 2: Map and GPX draw lines

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/map/MapLogic.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/map/TrackMapView.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/map/MapScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/GpxExport.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/map/MapLogicTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/GpxExportTest.kt`

- [x] write `trackGeoJson(lines)` tests: two lines → one `MultiLineString` feature with two parts, `[lon, lat]` order kept; one line → one part; a 1-point line is skipped; no drawable line → empty collection
- [x] write `buildGpx(lines, name)` tests: one `<trkseg>` per line; empty list → valid empty track; replace the `segmentId`-grouping tests (lines 50–75) with line-based ones
- [x] change `trackGeoJson` to take `List<List<TrackPointLike>>` and emit `MultiLineString` (keep the `StringBuilder` approach); update KDoc
- [x] change `buildGpx` to take lines and write one `<trkseg>` each; drop its `segmentId` grouping; update KDoc
- [x] change `MapScreen`/`TrackMapView` `track` param to `trackLines: List<List<TrackPointLike>>`; `dataBounds` gets `trackLines.flatten()`
- [x] in `MainActivity` keep `trackLinesNow` (lines) for the map and GPX, `trackUsable = trackLinesNow.flatten()` for the time span
- [x] run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` — must pass

### Task 3: `TrackFilterPreference`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/TrackFilterPreference.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/TrackFilterPreferenceTest.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [x] write tests: initial value comes from `load`; setter updates the flow and calls `save`
- [x] implement `TrackFilterPreference` modeled on `TrackProfilePreference`
- [x] add `trackFilterPreference by lazy` to `AppContainer`
- [x] run `./gradlew testDebugUnitTest` — must pass

### Task 4: Wire the toggle into MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] collect `trackFilterPreference.showAllPoints` next to `trackProfile` (line ~389) and pass value + setter into `Kolco24AppRoot`
- [x] pass `filter = !showAllPoints` to `trackLines` in `trackLinesNow` (line ~915) and `onShareTrack` (line ~1183); compute `hiddenCount`; new toast text
- [x] run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` — must pass

### Task 5: Settings switch

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add `ShowAllTrackPointsRow(checked, onCheckedChange)` copied from `EconomyModeRow` (duplicate, don't couple): title «Показывать все точки трека», subtitle «Без фильтрации выбросов GPS», suitable icon
- [x] place it right after `EconomyModeRow`; add `showAllTrackPoints` / `onShowAllTrackPointsChange` params and pass them from the host
- [x] run `./gradlew assembleDebug` — must pass (Compose UI untested by convention)

### Task 6: Map chip «Все точки»

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/map/MapScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add params `showAllPoints: Boolean`, `hiddenCount: Int`, `onToggleShowAll: () -> Unit`
- [x] add a `FilterChip` in its own `TopStart` slot (leave `NoMapBanner` at `TopCenter`; if the banner is shown, offset the chip below it): label `"Все точки" + if (hiddenCount > 0) " · +$hiddenCount" else ""`, semi-transparent background like the OSM attribution
- [x] hide the chip when `!showAllPoints && hiddenCount == 0`; with `showAllPoints` on, show it selected (so the user can switch back)
- [x] pass the new params from the `PAGE_MAP` call site (line ~1669); `frameKey` unchanged (no camera reframe)
- [x] run `./gradlew assembleDebug` — must pass

### Task 7: TrackCard «на карте N»

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add `shownPointCount: Int` param through `TeamScreen` → `TrackCard`; pass `trackUsable.size` from `MainActivity.kt:1705` (Settings `:1911` stays raw)
- [x] in the metrics row (`TrackCard.kt:213-221`) show «на карте N» under/next to the point count only when `shownPointCount != pointCount`; `RecordingHeader` stays unchanged
- [x] run `./gradlew assembleDebug` — must pass

### Task 8: Verify acceptance criteria
- [x] map, time span and GPX all use the same lines; toggle flips all three
- [x] no line is drawn between recording segments or across an unreachable step
- [x] Settings switch and map chip stay in sync
- [x] server upload path untouched (`TrackRepository.flushScope` unchanged)
- [x] run `./gradlew testDebugUnitTest`
- [x] run `./gradlew lintDebug`

### Task 9: [Final] Update documentation
- [x] `docs/design/DATA-NOTES.md` — `buildGpx` takes lines; replace the `filterPoints` note (line 29) with `trackLines` (chains, head/interior/tail rules, constants, known behaviour); add `TrackFilterPreference`
- [x] `docs/design/UI-NOTES.md` — `MapLogic.trackGeoJson` line-based `MultiLineString`, `TrackMapView`/`MapScreen` `trackLines` param, MainActivity `trackUsable`/`hiddenCount`, MapScreen chip, Settings row, TrackCard label
- [x] `CLAUDE.md` — add `TrackFilterPreference` to the prefs list in the Data module map
- [x] move this plan to `docs/plans/completed/` (deferred - moved by orchestrator after reviews)

## Post-Completion
*Informational only*

**Manual verification:**
- start recording indoors, then walk outside: the network cluster near the start is gone, no straight line from it
- walk or ride a loop with a stop/start in the middle: no long straight lines, and no line across the stop gap
- toggle the map chip: track redraws, camera stays put, Settings switch follows
- share GPX with the toggle on and off, compare point counts
- keep recording after a GPS jump: the new points appear right away as a separate line (no frozen tail)
- a car test above ~50 km/h breaks the track into separate lines — expected, filter is tuned for foot/bike
