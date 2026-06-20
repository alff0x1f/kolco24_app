# Checkpoint Color Support (mobile)

## Overview
- The backend added a `color` field to each checkpoint in the legend endpoint (`GET /app/race/<id>/legend/`). See `docs/checkpoint-color.md`.
- `color` is a **named semantic token** (not hex/RGB): `""` (default/none), `"red"`, `"blue"`, `"green"`, `"yellow"`, `"orange"`, `"purple"`. The app maps the token to its own palette.
- Purpose: in multi-discipline races (foot / bike / water) organizers group checkpoints visually by stage. Today all КП render identically.
- This change plumbs `color` from DTO → Room → UI and renders it as a **leading color bar** on each legend row.

## Context (from discovery)
- Single-activity Jetpack Compose app, Room as single source of truth, manual DI (`AppContainer`). minSdk 24, no `java.time` desugaring.
- Files/components involved:
  - `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt` — `CheckpointDto`
  - `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointEntity.kt` — entity (doubles as model)
  - `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt` — `version`, migrations
  - `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt` — `CheckpointDto.toEntity`
  - `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt` — `CheckpointRow` / `OpenCheckpointRow` / `LockedCheckpointRow`
  - `app/src/main/java/ru/kolco24/kolco24/ui/theme/Color.kt` — palette tokens
  - `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt` — instrumented migration guard
  - `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/{1..7}.json` — exported schemas (`exportSchema = true`)
- Patterns found:
  - Pure, Android-free logic separated from Compose and JVM-unit-tested (e.g. `ScanSession.kt`, `ui/teampicker/TeamPickerLogic.kt`). The token parser follows this split.
  - Each migration is hand-written SQL with an instrumented `MigrationTest` case + `runMigrationsAndValidate` against the exported schema JSON. KSP does **not** verify migration SQL; mismatches surface only at runtime.
  - `CheckpointDao.replaceAllForRace` is preserve-on-resync for offline reveals (`cost`/`description`).
- Dependencies identified: none new (no new libraries).

## Development Approach
- **Testing approach**: Regular (code first, then tests) — matches the codebase (pure logic + JVM tests, instrumented migration tests).
- Complete each task fully before moving to the next; make small, focused changes.
- **Every task includes new/updated tests** for its code changes (unit tests for pure logic, instrumented test for the migration).
- **All tests must pass before starting the next task.**
- Update this plan when scope changes during implementation.
- Maintain backward compatibility (additive DB column with default; DTO field defaulted).

## Testing Strategy
- **Unit tests** (JVM, `app/src/test`): `CheckpointColorTest` for `parseCheckpointColor` — every known token, `""` → null, unknown → null, case/whitespace tolerance.
- **Instrumented tests** (`app/src/androidTest`, needs emulator/device): add a 7→8 case to `MigrationTest` (data survives, `color` column exists with default `''`). Run via `./gradlew connectedDebugAndroidTest`.
- **No e2e framework** in this project (no Playwright/Cypress). The Compose host UI is untested by convention; the color bar is purely decorative, so no UI test is added. Visual check is a Post-Completion manual step.
- `./gradlew lintDebug testDebugUnitTest` must be green before finishing.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Keep the plan in sync with actual work.

## Solution Overview
- `color` is **race-scoped public data** (like `number`/`type`), present in **both** open and locked checkpoint branches — it is never hidden behind `enc`. So it stores on the `CheckpointEntity` row and needs no team-scoping and no preserve-on-resync handling.
- Token → pixel mapping lives app-side: a pure `parseCheckpointColor(token): CheckpointColor?` (null for `""`/unknown — forward-compatible, never crashes on a future token), and a small Compose map `CheckpointColor → Color`.
- The UI renders a thin (~4dp) leading vertical bar on each legend row. Neutral (`null`) → transparent bar → the row looks exactly as today (zero regression for the `color: ""` majority).
- Color is **purely decorative**: it does not affect scoring, the «Не взятые» filter, `taken` state, or `lockedCount`.

## Technical Details
- **Palette (saturated, single shade, same light & dark):** red `#E53935`, blue `#1E88E5`, green `#1F7A3D` (reuse existing `Tertiary`), yellow `#F4B400`, orange `#C65A2E` (reuse existing `OrangeCta`), purple `#8E44AD`. `""` / unknown → no bar. Exact hex can be tuned with design later.
- **Migration v7 → v8** — plain additive column (no table recreate, unlike `MIGRATION_3_4`/`MIGRATION_6_7`):
  ```sql
  ALTER TABLE checkpoints ADD COLUMN color TEXT NOT NULL DEFAULT ''
  ```
  This must match Room's generated `8.json` exactly (`color` `TEXT NOT NULL` default `''`).
- **Sync behavior (context, not a step):** the server bumped `_LEGEND_SCHEMA_VERSION` 1→2, so every client's stored legend ETag mismatches → one `200` refetch → colors populate automatically. The client migration backfills existing rows to `''` first, then that refetch overwrites with real colors. No manual cache invalidation needed.
- **No map screen** exists in the app, so the doc's "map markers" guidance does not apply; scope is the legend list only. Online scoring `/api/` does not send `color`, and the app must read color only from the legend.

## What Goes Where
- **Implementation Steps** (`[ ]`): DTO/entity/migration/mapping/parser/palette/UI plus their tests, schema export, lint/unit green.
- **Post-Completion** (no checkboxes): emulator-only instrumented run, manual visual check, design sign-off on exact hexes.

## Implementation Steps

### Task 1: Add `color` to the DTO and entity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointEntity.kt`

- [x] Add `val color: String = ""` to `CheckpointDto` (default `""` for forward-compat; field is documented as always present but a default keeps parsing robust). Update the KDoc to note `color` is public and appears in both open and locked branches.
- [x] Add `val color: String = ""` as the **last** field of `CheckpointEntity` (so the appended `ALTER TABLE ADD COLUMN` agrees with the generated `8.json` ordinal layout); update KDoc noting it is race-scoped public data (no team-scoping, no preserve-on-resync concern).
- [x] No standalone tests in this task (covered by Task 5 parser tests + Task 2 migration test); confirm `./gradlew assembleDebug` compiles after the entity change (KSP regenerates the schema delta).

### Task 2: Room migration v7 → v8 (additive `color` column)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/8.json` (generated by build)

- [x] Bump `@Database(version = 7 → 8)` in `AppDatabase`.
- [x] Add `val MIGRATION_7_8 = object : Migration(7, 8) { ... }` executing `ALTER TABLE checkpoints ADD COLUMN color TEXT NOT NULL DEFAULT ''`.
- [x] Register `MIGRATION_7_8` in the `addMigrations(...)` list in `build()`.
- [x] Build to generate `app/schemas/.../8.json` and commit it **before** running the migration test (`runMigrationsAndValidate` reads `8.json` to validate the resulting schema).
- [x] Write `migrate7To8_keepsDataAndAddsColorColumn` in `MigrationTest`, mirroring `migrate6To7_dropsTakenColumnAndKeepsCheckpointData`: seed a race + checkpoint at v7 via the **full prior chain** (`runMigrationsAndValidate(dbName, 7, true, MIGRATION_1_2 ... MIGRATION_6_7)`), then run `runMigrationsAndValidate(dbName, 8, true, MIGRATION_1_2 ... MIGRATION_7_8)` (all seven). Assert the prior checkpoint row survives and `color` exists defaulting to `''` (`SELECT color FROM checkpoints WHERE id = 1` → `''`; plus a `pragma_table_info('checkpoints')` check that `color` is present).
- [x] Run instrumented migration test: `./gradlew connectedDebugAndroidTest` — all 13 MigrationTest cases pass on emulator Medium_Phone_API_36.1 (including migrate7To8).

### Task 3: Map `color` through the repository

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`

- [x] In `CheckpointDto.toEntity`, pass `color = color`.
- [x] Confirm **no change** needed to `CheckpointDao.replaceAllForRace` (color always arrives from the server, nothing to preserve) and `reveal()` (color stored at insert) — add a one-line code comment only if it clarifies intent; do not modify the transaction.
- [x] No new test here (the mapping is exercised by the existing `LegendRepository`/`CheckpointDao` instrumented tests and Task 5 covers parsing); confirm compile.

### Task 4: Palette colors in the theme

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/theme/Color.kt`

- [x] Add the four new `Color` vals (red `#E53935`, blue `#1E88E5`, yellow `#F4B400`, purple `#8E44AD`) with clear names (e.g. `CpColorRed`, `CpColorBlue`, `CpColorYellow`, `CpColorPurple`). Reuse existing `Tertiary` (green) and `OrangeCta` (orange) — do not duplicate.
- [x] No test (static color constants).

### Task 5: Pure token parser + JVM tests

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/legend/CheckpointColor.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/legend/CheckpointColorTest.kt`

- [x] Create `CheckpointColor.kt` with `enum class CheckpointColor { RED, BLUE, GREEN, YELLOW, ORANGE, PURPLE }` and `fun parseCheckpointColor(token: String): CheckpointColor?` using `token.trim().lowercase()`, returning `null` for `""` and any unknown token.
- [x] Keep this file Android-free/pure (no Compose imports) so it is JVM-unit-testable, mirroring `ScanSession.kt` / `TeamPickerLogic.kt`.
- [x] Write `CheckpointColorTest`: each of the six known tokens maps to its enum; `""` → null; unknown (e.g. `"teal"`) → null; case-insensitive (`"RED"`, `"Red"`) and whitespace-tolerant (`" red "`) → `RED`.
- [x] Run `./gradlew testDebugUnitTest` — must pass before next task.

### Task 6: Render the leading color bar in the legend row

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt`

- [ ] Add the Compose mapping `CheckpointColor.barColor(): Color` **in `LegendScreen.kt`** (a Compose file) — never in the pure `CheckpointColor.kt`, which must stay Android-free; `parseCheckpointColor(cp.color)` → `null` means no bar.
- [ ] In `CheckpointRow`, wrap the existing row + divider so a leading `Box(Modifier.width(4.dp).fillMaxHeight().background(barColor ?: Color.Transparent))` sits on the left edge, applied to **both** `OpenCheckpointRow` and `LockedCheckpointRow` (color is public, shows pre-reveal). The 4dp bar is a **fixed gutter present in every row** (transparent when neutral) so spacing is consistent across colored and uncolored rows; reduce the row's existing leading horizontal padding by 4dp (and keep the divider's 76dp start aligned) so text alignment is identical to today and the "zero regression" claim holds literally.
- [ ] Confirm color does not touch scoring/filter/taken/lockedCount logic (no changes to `LegendList` metrics).
- [ ] Known minor UI detail (ship as-is): the first/last row's bar meets the rounded `CheckpointListCard` corner as a square — acceptable; note for design.
- [ ] No automated UI test (host UI untested by convention); visual verification is a Post-Completion manual step. Run `./gradlew lintDebug` — must pass.

### Task 7: Verify acceptance criteria
- [ ] `color` flows DTO → entity → Room → row; open and locked rows both show the bar; `""`/unknown render with no bar (no regression).
- [ ] Unknown/future token does not crash (parser returns null).
- [ ] Scoring, «Не взятые» filter, taken state, and `lockedCount` are unchanged.
- [ ] Run full unit suite: `./gradlew lintDebug testDebugUnitTest`.
- [ ] Run instrumented migration guard (emulator/device): `./gradlew connectedDebugAndroidTest`.

### Task 8: [Final] Update documentation
- [ ] Update `CLAUDE.md`: note `CheckpointDto`/`CheckpointEntity` carry `color`, Room is now **v8** with `MIGRATION_7_8` (additive `color` column), `8.json` committed, the new pure `ui/legend/CheckpointColor.kt` + `CheckpointColorTest`, and the legend leading-color-bar rendering. Adjust the existing v7/version references accordingly.
- [ ] Update `docs/checkpoint-color.md` "Согласовать с командой" only if the exact palette is confirmed (otherwise leave for design sign-off).
- [ ] Move this plan to `docs/plans/completed/`.

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification:**
- Run the app against a race whose legend includes colored КП; confirm each token shows the right bar on both open and locked rows, and `color: ""` rows look identical to before. Check light and dark themes.
- Confirm a checkpoint with an unfamiliar/future token renders neutrally (no crash) — can be simulated with a local mock response if no real data exists.

**Design sign-off:**
- Confirm the exact token → hex mapping (red/blue/green/yellow/orange/purple) and the 4dp bar width/placement with design; tune hexes in `Color.kt` if requested.

**Environment note:**
- `connectedDebugAndroidTest` (migration guard) requires an emulator/device; ensure it runs in CI or locally before merging the DB version bump.
