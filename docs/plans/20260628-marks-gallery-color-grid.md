# Marks Gallery — Color-Fill Grid Redesign

## Overview
Redesign the tile gallery on the **Отметки** tab from the current "white scorecard card + thin top color stripe" into a **full-color-fill grid**: the КП color (= stage/discipline of the multi-race) fills the *entire* tile, so same-discipline takes read as color "blobs" — a discipline map rather than a spreadsheet of mono numbers.

- **NFC takes** → a muted stage color fills the whole tile, КП token (`cost-number`) centered, take time bottom-right.
- **Photo takes** → the captured photo fills the whole tile, token + time over a bottom scrim. *(Photo marking is not shipped yet — build the layout now, defer the real image source; see Task 3.)*
- The grid runs **edge-to-edge** as a continuous color field; the metrics card above stays inset/rounded (unchanged).

Only the grid container and the tile composable change. The pure `marksToTiles` mapping, metrics card, empty state, ghost row, and NFC banner are untouched.

## Context (from discovery)
- **Single file to change:** `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt` — currently holds `TileGrid`, `ScorecardTile`, `NfcTileBody`, `PhotoTileBody`, `MiniCpBadge`, a private `CheckpointColor.barColor()`, `TileShape = RoundedCornerShape(10.dp)`, and the photo-seat colors (`PhotoTileTop/Bottom`, `RedBand`, `PhotoInk`).
- **Mapping is stable:** `marksToTiles(...)` (number/cost/kind/time/color) does not change → `app/src/test/java/ru/kolco24/kolco24/ui/marks/MarksMappingTest.kt` keeps passing as-is.
- **Color tokens:** the bright `CpColorRed/Blue/Yellow/Purple` + `Tertiary` (green) + `OrangeCta` live in `ui/theme/Color.kt` and feed the **thin bars** in `LegendScreen.kt`/`ProvisioningScreen.kt`. The new *muted fill* palette is screen-scoped — it lives as private vals in `MarksScreen.kt` (mirroring the existing `PhotoTileTop` etc.), so the legend/provisioning bars are unaffected.
- **`CheckpointColor`** enum (RED/BLUE/GREEN/YELLOW/ORANGE/PURPLE) + `parseCheckpointColor(token): CheckpointColor?` are pure (`ui/legend/CheckpointColor.kt`); `parseCheckpointColor("")`/unknown → `null` (the neutral tile).
- **No image loader** (Coil/Glide) is in the project — confirmed via `app/build.gradle.kts` / `gradle/libs.versions.toml`. Real photo rendering is therefore deferred.
- **Dark mode** is live (`Kolco24Theme(darkTheme=...)`); `isSystemInDarkTheme()` is available in composables.

## Development Approach
- **Testing approach:** Regular (code first, then tests) — matches the repo, where pure logic is unit-tested and Compose UI is untested by convention.
- Extract the color→(fill,text) decision as a **pure function** so it carries a unit test; keep the rest (composables) untested per convention.
- Complete each task fully (incl. its tests) before the next; all tests pass before moving on.
- Keep the diff scoped to `MarksScreen.kt` (+ its test) — no theme/legend/provisioning changes.
- **Each of Tasks 2–4 leaves the file compiling:** Task 2 wires the `TileGrid` → `ColorTile` call site immediately (keeping the old `PhotoTileBody` until Task 3 reworks it), so `assembleDebug` is a real gate at the end of every task, not deferred.
- **`TileShape` is retained** — it still backs the out-of-scope `GhostTile`; only `ScorecardTile`'s use goes away (the new tile is `RectangleShape`).

## Testing Strategy
- **Unit tests:** the new pure `tileFill(...)` mapping (success cases for each color + neutral light/dark + the yellow/neutral ink rule). `MarksMappingTest` stays green (regression guard that the tile data mapping is unchanged).
- **No e2e:** project has no UI e2e harness; Compose tiles are visually verified by build + manual run.
- **Gates before done:** `./gradlew lintDebug` and `./gradlew testDebugUnitTest` must pass.

## Progress Tracking
- mark completed items `[x]` immediately
- `➕` prefix for newly discovered tasks, `⚠️` for blockers
- keep this file in sync if scope shifts during implementation

## Solution Overview
The tile becomes the color. A `TileFill(fill: Color, text: Color)` value + a pure `tileFill(color: CheckpointColor?, darkTheme: Boolean): TileFill` resolves each take's color token to a flat fill and a fixed (non-luminance) text color — white on red/orange/blue/green/purple, dark ink on yellow and neutral. Neutral is the only theme-aware fill (light grey in light mode, charcoal in dark) so it never glows as a bright blob among the colors.

The grid is a 4-column `Column`/`Row` layout with **2dp gaps and 0dp radius**, sitting on a **grout** background (a slightly darker cool-grey seam) that shows through the gaps — so a big same-color cluster (e.g. 15 green) reads as one tiled-wall region, not a printed slab, while the per-tile tokens keep it from being featureless. The grid is **edge-to-edge** (no horizontal padding). A per-tile gradient was deliberately rejected: it repeats into corduroy/banding across a cluster, degrading exactly the blob case the design is built around.

## Technical Details
- **Muted fill palette** (private vals in `MarksScreen.kt`; fixed across light/dark except neutral):
  - red `#CB4233` · orange `#C15A2E` · blue `#2F6CAE` · green `#2E9E57` · yellow `#C99A1E` · purple `#7C5AC0`
  - ink `#161A1F` (text on yellow/neutral) · white text on the rest
  - neutral light `#D6DCE4`, neutral dark `#2A323C` (text: ink / light grey resp.)
  - grout light `#C2CBD5`, grout dark `#11161B`
- **Resolved dark mode (NOT `isSystemInDarkTheme()`):** the app applies a manual theme override at the call site — `Kolco24Theme(darkTheme = mode.isDark(isSystemInDarkTheme()))` (`MainActivity.kt:266`). So a tile must read the **resolved** theme, not the OS one, or neutral/grout will mismatch a manual Light/Dark choice. Use a small composable helper `@Composable private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f` (the `colorScheme` passed by `Kolco24Theme` is already the resolved one) and pass its result into `tileFill(...)`/`gridGrout(...)`. `androidx.compose.ui.graphics.luminance`.
- **Pure mappings (`internal` for the test):** `internal data class TileFill(val fill, val text)`, `internal fun tileFill(color, darkTheme): TileFill`, and `internal fun gridGrout(darkTheme: Boolean): Color` — both take a plain `darkTheme: Boolean` (no Compose theme lookup inside), so they are JVM-unit-testable and can't repeat the `isSystemInDarkTheme()` mistake. `internal` (not `private`) so `TileFillTest` in the same module can call them.
- **Token rendering:** `cost-number` as an `AnnotatedString` so the hyphen gets a 50%-alpha `SpanStyle`; RobotoMono Bold ~23sp, letterSpacing -0.8sp, centered (`includeFontPadding=false` + centered/trimmed `LineHeightStyle`, as today). Time: RobotoMono Medium ~10.5sp at 78% of the tile text color, bottom-end with 8dp/6dp insets.
- **Photo tile (token + time live INSIDE the bottom scrim):** charcoal placeholder fill (reuse `PhotoTileTop/Bottom`) + a bottom scrim (`Brush.verticalGradient(transparent → black ~60%)`). The token is **bottom-leading** and the time **bottom-end**, both within the scrim band — *not* centered on the tile, so they stay legible once a real photo replaces the placeholder (a centered token would float over the bright middle of a photo with no scrim behind it). This is a deliberate departure from the NFC tile's centered hero: on a photo tile the photo is the hero and the token reads as a caption. A `// TODO(photo): render mark.photoPath when photo marking ships (add Coil)` marks the deferred image source.
- **Grid container:** outer `Column` with `background(grout)`, `verticalArrangement = spacedBy(2.dp)`, no horizontal padding; each `Row` `spacedBy(2.dp)`; trailing slots in the last row filled with `grout`-colored spacers so the seam grid stays regular. Tiles are `RectangleShape` (0dp).
- **Out of scope (unchanged):** `MetricsCard`, `MarksEmpty`, `LocationNudge`, `TrackNudge`, `ScorecardGhostRow`/`GhostTile`, `NfcUnavailableBanner`, `marksToTiles`.

## What Goes Where
- **Implementation Steps** (checkboxes): all changes are in `MarksScreen.kt` + its unit test, plus a CLAUDE.md doc update.
- **Post-Completion** (no checkboxes): manual visual verification on device/emulator (light + dark, a large same-color cluster); the future photo-image wiring when photo marking lands.

## Implementation Steps

### Task 1: Muted fill palette + pure `tileFill` mapping

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/marks/TileFillTest.kt`

- [ ] add private fill-palette vals (red/orange/blue/green/yellow/purple, ink, neutral light/dark, grout light/dark) near the existing `PhotoTileTop` block
- [ ] add `internal data class TileFill(val fill: Color, val text: Color)` and a pure `internal fun tileFill(color: CheckpointColor?, darkTheme: Boolean): TileFill` — white text on red/orange/blue/green/purple, ink on yellow; `null` → neutral (light grey/ink in light, charcoal/light-grey in dark). `internal` (not `private`) so the test can call it; takes a plain `darkTheme: Boolean` (no Compose lookup inside)
- [ ] add a pure `internal fun gridGrout(darkTheme: Boolean): Color` (light `#C2CBD5` / dark `#11161B`) so the grout seam is resolved the same testable way as `tileFill`, not via `isSystemInDarkTheme()`
- [ ] remove the now-unused private `CheckpointColor.barColor()` (replaced by `tileFill`)
- [ ] remove the now-orphaned theme imports `CpColorRed/Blue/Yellow/Purple` (only `barColor` referenced them — `OrangeCta`/`Tertiary` stay in use elsewhere) so `lintDebug` stays clean
- [ ] write `TileFillTest`: each color → expected fill + text; yellow → ink; neutral light vs dark differs; non-neutral fills identical in light vs dark; **`gridGrout` light ≠ dark**
- [ ] run `./gradlew testDebugUnitTest` — `TileFillTest` + `MarksMappingTest` must pass before Task 2

### Task 2: `ColorTile` composable — NFC (color-fill) body + call-site

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] add the `@Composable private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f` helper (resolves the *applied* theme, respecting the manual Light/Dark override)
- [ ] replace `ScorecardTile`/`NfcTileBody` with a `ColorTile(mark)` that resolves `tileFill(mark.color, isDarkScheme())` (**not** `isSystemInDarkTheme()`), fills the square (`aspectRatio(1f)`, `RectangleShape`, flat `background(fill)`, no border/stripe/elevation) and routes `mark.kind` → the new NFC body vs the existing `PhotoTileBody` (kept as-is until Task 3)
- [ ] render the `cost-number` token as an `AnnotatedString` with the hyphen at 50% alpha, centered, RobotoMono Bold ~23sp / -0.8sp / `includeFontPadding=false` + centered-trimmed `LineHeightStyle`, color = `tileFill.text`
- [ ] render the take time bottom-end (8dp end / 6dp bottom), RobotoMono Medium ~10.5sp, color = `tileFill.text` at 78% alpha
- [ ] update the `TileGrid` call site `ScorecardTile(mark)` → `ColorTile(mark)`; remove the leading-stripe `Box`; **keep `TileShape`** (still used by the out-of-scope `GhostTile`); keep `scoreToken` only if still used, else inline
- [ ] fix the now-stale `GhostTile`/`ScorecardGhostRow` KDoc/comments that reference `[ScorecardTile]` and its "top stripe" (the ghost row is behaviorally unchanged, but the comments point at the deleted composable) — repoint them at `ColorTile`
- [ ] build: `./gradlew assembleDebug` compiles, `./gradlew testDebugUnitTest` green before Task 3 (no Compose UI test — untested per convention)

### Task 3: Photo tile body (layout now, image deferred)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] rework `PhotoTileBody` to fill the whole tile: charcoal placeholder (`Brush.verticalGradient(PhotoTileTop, PhotoTileBottom)`) + a bottom scrim (`transparent → black ~60%`)
- [ ] place the `cost-number` token **bottom-leading** and the time **bottom-end**, both **inside the bottom scrim**, in white (decided: not centered — keeps them legible once a real photo replaces the placeholder); remove `MiniCpBadge` if no longer used
- [ ] add `// TODO(photo): fill with mark.photoPath image once photo marking ships (add Coil dependency)`
- [ ] build: `./gradlew assembleDebug` compiles before Task 4 (no Compose UI test)

### Task 4: Edge-to-edge grout grid container

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] rework `TileGrid`: outer `Column` with `background(gridGrout(isDarkScheme()))` and `verticalArrangement = spacedBy(2.dp)`, **no horizontal padding** (edge-to-edge); each `Row` `horizontalArrangement = spacedBy(2.dp)`
- [ ] fill trailing empty cells in the last row with empty `Box(weight=1f)` spacers (they show the `Column`'s grout background through, so the seam grid stays regular to the edge)
- [ ] confirm the `LazyColumn` item for the grid adds no side padding around it (the metrics card keeps its own inset; grid sits flush)
- [ ] build: `./gradlew assembleDebug` (or `lintDebug`) — must compile before Task 5
- [ ] run `./gradlew testDebugUnitTest` — all unit tests green

### Task 5: Verify acceptance criteria
- [ ] grid is edge-to-edge; tiles are flat color, 0dp radius, 2dp grout gaps; metrics card unchanged
- [ ] all 6 colors + neutral render; yellow/neutral use dark ink; neutral + grout flip in dark mode; colored fills identical light/dark
- [ ] **manual theme override respected:** set Settings → Тема to LIGHT and DARK explicitly; neutral tiles + grout follow the chosen theme (not the OS theme) — guards the `isDarkScheme()` vs `isSystemInDarkTheme()` fix
- [ ] token shows `cost-number` with dimmed hyphen in RobotoMono; time bottom-right legible on every fill
- [ ] photo body fills the tile with placeholder + bottom scrim carrying the bottom-leading token + time (no real image yet, TODO present)
- [ ] empty state / ghost row / NFC banner / metrics still work and are visually unchanged
- [ ] run `./gradlew lintDebug` and `./gradlew testDebugUnitTest` — both pass

### Task 6: [Final] Update documentation
- [ ] update the `ui/marks/MarksScreen.kt` section of `CLAUDE.md` to describe the color-fill grid (NFC color tiles + photo-fill tiles, edge-to-edge grout grid, muted screen-scoped palette, `tileFill` mapping) and that `barColor` here is replaced (legend/provisioning copies unchanged)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Informational — manual/external, no checkboxes*

**Manual verification:**
- run the app on device/emulator, select a team with takes; verify a **large same-color cluster** (e.g. many green) reads as one tiled region, not a slab, in both light and dark mode
- check token + time legibility on yellow (ink) and on the darkest fills (purple/blue)

**Future work (separate effort):**
- when photo marking ships: add an image loader (Coil), render `mark.photoPath` into the photo tile (replacing the placeholder), and decide the token/time overlay against real imagery
