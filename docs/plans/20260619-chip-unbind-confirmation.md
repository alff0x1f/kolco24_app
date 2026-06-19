# Guard accidental chip unbind on the Команда tab

## Overview
On the «Команда» tab, tapping a team member whose NFC chip is bound **immediately deletes the
binding** — the entire `MemberRow` is `clickable(onClick = onUnbind)` and the unbind writes to Room
with no confirmation and no undo. A single accidental tap silently destroys a binding the user set up
before the race.

This change replaces tap-to-unbind with a **deliberate gesture + confirmation**:
- Regular tap on a bound member does nothing.
- **Long-press** on a bound member opens an M3 `AlertDialog` confirming the unbind.
- The actual Room delete happens only after the user confirms «Отвязать».

Benefit: a binding can no longer be lost by an accidental tap. Two barriers now stand between the
user and a destructive write — an intentional long-press and an explicit confirmation.

## Context (from discovery)
- Single-activity Jetpack Compose app; screen state lives in `MainActivity.kt` (no ViewModel/Nav).
- `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt` — `MemberRow` (line ~289) wraps the
  bound row in `.then(if (bound) Modifier.clickable(onClick = onUnbind) else Modifier)` and shows a
  trailing `ChevronRight` for bound rows (lines ~352-358) that misleadingly implies "tap to open".
- `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — the «Команда» tab host (the `2 ->
  TeamScreen(...)` branch, lines ~312-330). `onUnbindMember` currently calls
  `bindingRepo.unbind(teamId, member.numberInTeam)` on `container.applicationScope` directly.
  Existing overlay state: `bindSlot`, `confirmTeamId`, `showSettings`, `showScan`, `teamFlowStep`,
  each `rememberSaveable` and guarded by a layered `BackHandler` (see the bind-chip overlay at
  lines ~465-475). `teamForTab` (line 198) resolves the current team; `bindings` (line ~213) is the
  `Map<Int, MemberChipBindingEntity>` keyed by `numberInTeam`.
- Reference pattern: the bind-chip overlay (`bindSlot` + `BackHandler` + member-resolve-from-slot)
  and `BindChipSheet` (how the host resolves a member from a slot and styles confirm actions).
  Documented in CLAUDE.md «Bind-chip overlay».

## Development Approach
- **Testing approach**: Regular, **no new automated tests**. This change is UI/state-only — the
  decision logic (`decideBind`) is untouched, and there is no new pure function to unit-test. The
  `onUnbindMember: (TeamMemberItem) -> Unit` callback signature is unchanged, so existing tests and
  previews keep compiling. (This deliberately overrides the default "tests required per task" rule:
  there is no testable non-UI logic introduced. Verification is via lint + build + manual check.)
- Make small, focused changes; keep backward compatibility of the `TeamScreen` API.
- Build/lint after the change.

## Testing Strategy
- **Unit tests**: none added — no new pure logic (see Development Approach).
- **e2e tests**: project has no UI e2e harness; manual verification only (see Post-Completion).
- **Build gate**: `./gradlew lintDebug` and `./gradlew assembleDebug` must pass.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- `➕` prefix for newly discovered tasks, `⚠️` for blockers.
- Keep this plan in sync with actual work.

## Solution Overview
The unbind becomes a host-driven confirmation flow mirroring the existing `bindSlot` overlay
convention. `MemberRow` swaps `clickable` for `combinedClickable` so that a long-press (not a tap)
requests the unbind, and drops the now-misleading chevron. `MainActivity` holds a new
`unbindSlot: Int?` state; `onUnbindMember` only sets it (no write). When set and the member resolves,
an `AlertDialog` is shown; confirming performs the Room delete on `applicationScope`. A guarded
`BackHandler` dismisses the dialog, layered with the other overlay guards.

## Technical Details
- `combinedClickable(onClick = {}, onLongClick = { onUnbind() })` requires
  `androidx.compose.foundation.combinedClickable` and an `@OptIn(ExperimentalFoundationApi::class)`
  on `MemberRow` (or a file-level opt-in). Default ripple is kept (touch feedback on press/hold).
- `unbindSlot` is `Int?` (the member's `numberInTeam`), `rememberSaveable` without a custom Saver,
  consistent with `bindSlot`/`confirmTeamId`.
- Dialog data: member name from `teamForTab?.members?.find { it.numberInTeam == unbindSlot }`;
  `participantNumber` + `nfcUid` from `bindings[unbindSlot]`.
- Confirm writes via `container.applicationScope.launch { bindingRepo.unbind(teamId, slot) }` (the
  delete must outlive the closing dialog, per the `selectTeam`/existing-unbind convention), then
  `unbindSlot = null`.

## What Goes Where
- **Implementation Steps**: `TeamScreen.kt` gesture/chevron change, `MainActivity.kt` state + dialog +
  BackHandler, CLAUDE.md doc update, build/lint verification.
- **Post-Completion**: manual on-device verification of tap/long-press/confirm/cancel behavior.

## Implementation Steps

### Task 1: Replace tap-to-unbind with long-press in `MemberRow`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`

- [x] add import `androidx.compose.foundation.combinedClickable` and
  `androidx.compose.foundation.ExperimentalFoundationApi`
- [x] annotate `MemberRow` with `@OptIn(ExperimentalFoundationApi::class)`
- [x] in `MemberRow` (line ~289), replace
  `.then(if (bound) Modifier.clickable(onClick = onUnbind) else Modifier)` with
  `.then(if (bound) Modifier.combinedClickable(onClick = {}, onLongClick = onUnbind) else Modifier)`
- [x] remove the bound-branch trailing `ChevronRight` (lines ~352-358) so bound rows have no trailing
  icon; keep the unbound-branch «Привязать» `OutlinedButton` untouched
- [x] remove the now-unused `Icons.Filled.ChevronRight` import **only if** no longer referenced in this
  file (it is still used by `MiscRow` — keep the import in that case)
- [x] update the `MemberRow`/`TeamScreen` KDoc: "tapping the row unbinds" → "long-press the row to
  unbind (host confirms)"

### Task 2: Add confirmation dialog + state in `MainActivity`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `var unbindSlot by rememberSaveable { mutableStateOf<Int?>(null) }` next to `bindSlot`
  (line ~223)
- [ ] reset it on team switch: extend the existing `LaunchedEffect(selectedTeamId) { bindSlot = null }`
  (line 226) to also set `unbindSlot = null` (a switched-away team's slot must not linger)
- [ ] change `onUnbindMember` (lines ~321-327) to only set `unbindSlot = member.numberInTeam`
  (remove the direct `bindingRepo.unbind(...)` call)
- [ ] reset `unbindSlot = null` in the `onScanClick` handler (line ~303) alongside the other overlay
  resets, so the scan FAB never opens behind a stale dialog
- [ ] add an `AlertDialog` block (after the bind-sheet block, ~line 600) shown when
  `unbindSlot != null` **and** the member resolves from `teamForTab?.members.find { it.numberInTeam == unbindSlot }`:
  title «Отвязать чип?»; text = member name + mono «№{participantNumber} · {uid}» (from
  `bindings[unbindSlot]`) + «Чип можно будет привязать заново.»; confirm button «Отвязать»
  (`MaterialTheme.colorScheme.error`) → `container.applicationScope.launch { bindingRepo.unbind(teamId, slot) }`
  then `unbindSlot = null`; dismiss button «Отмена» and `onDismissRequest` → `unbindSlot = null`
- [ ] add `BackHandler(enabled = unbindSlot != null && !showScan && !showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null) { unbindSlot = null }`,
  layered consistently with the existing bind-slot `BackHandler` guards (line ~473)
- [ ] build: `./gradlew assembleDebug` must compile (resolves opt-in + new imports)

### Task 3: Verify acceptance criteria
- [ ] `./gradlew lintDebug` passes
- [ ] `./gradlew assembleDebug` passes
- [ ] confirm a regular tap on a bound row no longer triggers any Room write (code review of the
  `combinedClickable` `onClick = {}` path)
- [ ] confirm the dialog reads name/№/uid from the resolved member + `bindings[unbindSlot]`

### Task 4: [Final] Update documentation
- [ ] update CLAUDE.md: in the `TeamScreen.kt` bullet, change "tapping the row calls `onUnbindMember`"
  to describe long-press → host confirmation dialog, and note bound rows no longer show a chevron; in
  the «Bind-chip overlay» section, document the new `unbindSlot` state + its `BackHandler` guard
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual, on-device — no checkboxes.*

**Manual verification:**
- Bound member: single tap → nothing happens (no delete, ripple flashes only).
- Bound member: long-press (~0.5s) → «Отвязать чип?» dialog appears with correct name/№/uid.
- Dialog «Отвязать» → binding removed, hero «N / total с чипом» counter decrements.
- Dialog «Отмена» / scrim tap / system back → binding kept, dialog closes.
- Switch team while a dialog could be pending → no stale dialog for the previous team.
