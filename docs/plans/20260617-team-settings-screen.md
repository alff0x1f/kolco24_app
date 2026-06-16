# Move «Сменить команду» into a Settings (Настройки) overlay

## Overview
Today the «Команда» tab shows a prominent standalone «Сменить команду» row (`SwitchTeamRow`) right under the team hero card, plus a non-functional «Настройки» row in the «Прочее» section. This change:
- removes the standalone «Сменить команду» row from the «Команда» tab,
- introduces a new full-screen **Settings (Настройки)** overlay,
- makes the existing «Настройки» row open that overlay,
- places «Сменить команду» inside Settings as its single functional entry.

This realigns the «Команда» tab with the original hi-fi design (`assets/template_v2.html:1700`), which never had a standalone swap row. No data-layer changes; adds exactly one boolean of UI state.

## Context (from discovery)
- **No Navigation Compose.** The app composes full-screen overlays inside a single `Box` in `MainActivity`, driven by `rememberSaveable` flags/enums (the scan overlay and the team-picker flow both use this pattern).
- Files involved:
  - `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt` — `item("switch")` block (~lines 95–105) renders `SwitchTeamRow` (~lines 216–258) wired to `onChangeTeam`; «Прочее» `SectionCard` has a `MiscRow` for «Настройки» (subtitle "Соревнование, сервер, NFC", ~line 118). `MiscRow` currently has no `onClick`.
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — `TeamScreen(...)` called ~line 219 with `onChooseTeam`/`onChangeTeam`; overlay flow uses `teamFlowStep` (`TeamFlowStep` enum `None`/`CompPicker`/`TeamPicker`), `pickerRaceId`, `confirmTeamId`, `showScan` (all `rememberSaveable`); `BackHandler` chain ~lines 243–246; `onScanClick` ~line 209 resets `teamFlowStep`/`confirmTeamId`.
- Related patterns: overlays render after `Scaffold` in the same `Box`; `BackHandler`s are registered in priority order (later = wins when enabled); writes that must outlive a closing overlay run on `container.applicationScope`.
- Dependencies: none new. `SectionCard` helper stays in `TeamScreen.kt` (still used there). `SwitchTeamRow` moves conceptually into Settings.

## Development Approach
- **Testing approach**: Regular (code first). This is a pure Jetpack Compose UI/wiring change — stateless composables plus one `rememberSaveable` boolean. It introduces **no new pure/unit-testable logic** (consistent with CLAUDE.md: trivial composables and host wiring are not unit-tested; only pure helpers like `TeamPickerLogic`/converters are). Therefore no new unit tests are added; verification is build + lint + manual smoke (see Testing Strategy).
- Complete each task fully before the next; keep changes small and focused.
- **CRITICAL: update this plan file if scope changes during implementation.**
- Maintain backward compatibility of the existing team-picker flow (the picker is still reachable, now from inside Settings).

## Testing Strategy
- **Unit tests**: none added — no new pure logic surface (see Development Approach). Do **not** fabricate tests for trivial stateless composables; that would diverge from project convention.
- **Build/lint gates** (must pass before completion):
  - `./gradlew lintDebug`
  - `./gradlew assembleDebug`
- **Instrumented tests**: unaffected (no Room/schema change), so `connectedDebugAndroidTest` is not required for this change. Run only if convenient.
- **Manual smoke** (see Post-Completion): Команда tab no longer shows the standalone swap row; «Настройки» opens the overlay; «Сменить команду» inside Settings opens the comp picker; back navigation behaves correctly with scan/picker overlays.

## Progress Tracking
- mark completed items `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document blockers with ⚠️ prefix
- keep plan in sync with actual work

## Solution Overview
A new stateless `SettingsScreen` composable is added and rendered as a full-screen overlay in `MainActivity`, gated by a new `showSettings: Boolean` (`rememberSaveable`). The «Настройки» `MiscRow` on the «Команда» tab opens it; the standalone `SwitchTeamRow` and its `item("switch")` block are deleted. «Сменить команду» lives inside Settings and, when tapped, closes Settings and opens the existing comp-picker flow (`teamFlowStep = CompPicker`). Layering and a dedicated `BackHandler` ensure Settings sits beneath the picker/scan overlays and dismisses cleanly.

## Technical Details
- New state: `var showSettings by rememberSaveable { mutableStateOf(false) }` in `MainActivity`.
- `SettingsScreen(onBack: () -> Unit, onChangeTeam: () -> Unit, modifier: Modifier = Modifier)`.
- `onChangeTeam` body in `MainActivity`: `showSettings = false; pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker` (same as the old `onChangeTeam`, prefixed with closing Settings).
- Render order in the `Box`: `SettingsScreen` overlay **before** the `CompPicker`/`TeamPicker`/`TeamSwitchSheet` blocks so the picker draws on top when both are active.
- `BackHandler(enabled = showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan) { showSettings = false }`.
- `onScanClick` reset also sets `showSettings = false` so the scan FAB never opens behind a stale Settings overlay.
- `MiscRow` gains an `onClick: () -> Unit` param (default `{}` so «Справка» stays inert).
- «Настройки» subtitle: "Соревнование, сервер, NFC" → "Сменить команду".

## What Goes Where
- **Implementation Steps** (`[ ]`): new screen file, `TeamScreen.kt` edits, `MainActivity.kt` wiring, build/lint verification, docs.
- **Post-Completion** (no checkboxes): manual on-device/emulator smoke testing of overlay + back behavior.

## Implementation Steps

### Task 1: Create the Settings (Настройки) overlay screen

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`

- [x] create `SettingsScreen(onBack, onChangeTeam, modifier)` — `Column(Modifier.fillMaxSize())` with a `TopAppBar(title = { Text("Настройки") })` and a navigation back arrow (`Icons.AutoMirrored.Filled.ArrowBack`) → `onBack`
- [x] add a single inset section card titled «Команда» containing a swap-team row (charcoal `SwapHoriz` icon avatar, «Сменить команду» / «Выбрать другую команду соревнования», trailing `ChevronRight`), `clickable` → `onChangeTeam` — copy the ~15-line row locally (no shared file; duplication accepted per design)
- [x] match existing visual tokens (`surfaceContainerLow` card, `shapes.large`, padding mirroring `TeamScreen`'s `SectionCard`/`SwitchTeamRow`)
- [x] (no unit tests — stateless UI, see Testing Strategy)

### Task 2: Remove the standalone swap row from the «Команда» tab

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`

- [x] delete the `item("switch") { ... }` block (the `Surface` wrapping `SwitchTeamRow`)
- [x] delete the `SwitchTeamRow` private composable
- [x] remove the now-unused `onChangeTeam` parameter from `TeamScreen` and add `onOpenSettings: () -> Unit`
- [x] add `onClick: () -> Unit = {}` to `MiscRow` **and apply `.clickable(onClick = onClick)` to its `Row` modifier** (the row is static today — the param alone won't make it tappable); wire the «Настройки» `MiscRow` to `onOpenSettings`, leave «Справка» default
- [x] change the «Настройки» subtitle "Соревнование, сервер, NFC" → "Сменить команду"; remove only the now-unused `SwapHoriz` import — **keep `ChevronRight` and `clickable`** (still used by `MiscRow`)
- [x] (no unit tests — stateless UI)

### Task 3: Wire the Settings overlay into MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add `var showSettings by rememberSaveable { mutableStateOf(false) }`
- [x] update the `TeamScreen(...)` call: drop `onChangeTeam`, add `onOpenSettings = { showSettings = true }`
- [x] render `SettingsScreen(onBack = { showSettings = false }, onChangeTeam = { showSettings = false; pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker })` **before** the CompPicker/TeamPicker/TeamSwitchSheet blocks
- [x] add `BackHandler(enabled = showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan) { showSettings = false }`
- [x] add `showSettings = false` to the `onScanClick` reset; add the `SettingsScreen` import
- [x] (no unit tests — host wiring)

### Task 4: Verify acceptance criteria
- [x] «Команда» tab no longer shows the standalone «Сменить команду» row; «Настройки» row present with subtitle «Сменить команду»
- [x] run `./gradlew lintDebug` — must pass
- [x] run `./gradlew assembleDebug` — must pass
- [x] confirm no leftover references to the removed `onChangeTeam` param / `SwitchTeamRow` (only remaining `onChangeTeam` is `SettingsScreen`'s own param — expected)

### Task 5: [Final] Update documentation
- [ ] update `CLAUDE.md` (`MainActivity.kt` overlay description + `ui/team`/new `ui/settings` bullets) to document the Settings overlay, `showSettings` state, and the relocated «Сменить команду»
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention — no checkboxes, informational only*

**Manual verification** (emulator/device):
- Open «Команда» (with a selected team) → tap «Настройки» → Settings overlay appears over the tab.
- Inside Settings, tap «Сменить команду» → Settings closes and the comp picker opens at `selectedRaceId`.
- System back from Settings returns to the «Команда» tab.
- With Settings open, the scan FAB / team-picker flow still take precedence and back order is correct.
