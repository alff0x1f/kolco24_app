# Photo-Mark Rotation Fix

## Overview
- Two related bugs around device rotation and the photo-mark capture flow (`ui/photo/PhotoCaptureScreen.kt`):
  1. **Sideways photos**: a frame shot while holding the phone in landscape is saved on its side. `capture()` feeds `imageCapture.targetRotation` from `previewView.display?.rotation`, which reflects the *window's* orientation, not the phone's physical tilt — wrong whenever they disagree (auto-rotate off today; permanently wrong once the app is portrait-locked below, since the window will never rotate again).
  2. **Kicked out of the capture screen on rotation**: `MainActivity.kt`'s `LaunchedEffect(selectedTeamId)` (lines 725-737) unconditionally resets a batch of overlay flags — including `photoCaptureMarkId = null`, which closes the camera overlay — on every firing. `selectedTeamId`'s `collectAsState()` briefly resets to `null` on every Activity recreation (a documented transient blip, already guarded for the track-recording-stop branch in the same effect at lines 730-734, but not for the overlay resets), and this app has no `android:configChanges`, so any physical rotation recreates the Activity and fires this effect with a transient `null`.
- The user wants the whole app portrait-only going forward ("I don't want to maintain landscape mode, it looks ugly now") — so the fix is an app-wide orientation lock (which also eliminates the rotation trigger for bug 2), plus a defensive guard against *other* config-change-triggered recreates (multi-window, font-scale), plus physical-tilt tracking so captured JPEGs still come out upright once the window itself never rotates.
- A pre-existing draft note (`docs/plans/fix-rotate-bug.md`, deleted as part of this plan) described a *different* symptom — the NFC scan-session timer/state resetting on rotation — and proposed a `ScanViewModel` refactor to survive Activity recreation. The app-wide portrait lock here eliminates the **rotation-triggered** Activity recreate for that bug (no physical rotation ⇒ no config change from orientation ⇒ nothing to reset). It does **not** close every recreate path: `ScanScreen.kt`'s session/countdown/completed state is held in plain `remember` (not `rememberSaveable`), so a *non-orientation* recreate (system font-scale change, multi-window resize, a `uiMode` flip when `ThemeMode == SYSTEM`, or a process-death kill) still resets it. This residual gap is judged acceptable because the take itself is persisted to Room at scan time (`startKpTake`) and `isComplete` is a cosmetic dismiss gate only — scoring is never lost, only the in-progress countdown UI. On that basis, deleting the draft and skipping the `ScanViewModel` refactor is an informed trade-off, not a claim that every scan-reset path is closed. No `ScanViewModel` work is in scope here.

## Context (from discovery)
- Files/components involved:
  - `app/src/main/AndroidManifest.xml` — `<activity android:name=".MainActivity">` (no `screenOrientation` set today)
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt:725-737` — `LaunchedEffect(selectedTeamId)` overlay-reset effect
  - `app/src/main/java/ru/kolco24/kolco24/ui/photo/PhotoCaptureScreen.kt` — `capture()` (line 225 area), CameraX bind `DisposableEffect`s
  - `app/src/main/java/ru/kolco24/kolco24/data/marks/PhotoStorage.kt` — `writeDownscaledJpeg`/`prepareBitmap` (lines 90-105) already bakes whatever rotation degrees it's given into pixels correctly; **not modified** by this plan
- Patterns found:
  - Pure, Android-free logic is JVM-tested even when it lives in a file that also contains untested Android adapter code (`PhotoStorage.kt` mixes pure `scaledDimensions`/`orphanPhotoDirs`, tested by `PhotoStorageTest.kt`, with untested `Bitmap`/file I/O). The new rotation-bucketing logic follows the same split.
  - Compose Android adapters (camera binding, `DisposableEffect` lifecycle) are untested by convention.
  - `rememberSaveable` is the app's only state-survival mechanism across Activity recreation (no ViewModel) — the `lastRealTeamId` guard follows this existing idiom exactly.
- Dependencies identified: none new. `android.hardware.OrientationEventListener` and `android.view.Surface` are both already-available framework APIs (`Surface` already imported in `PhotoCaptureScreen.kt`).

## Development Approach
- **Testing approach**: Regular (code first, then tests) — matches the codebase convention.
- Complete each task fully, run tests, before moving to the next.
- Every task with new/changed logic includes new/updated tests; Android-adapter-only changes are exempt (untested by convention), same as everywhere else in this codebase.
- Update this plan if scope changes during implementation.

## Testing Strategy
- **Unit tests** (JVM, `app/src/test`): new `PhotoCaptureScreenTest.kt` for `bucketOrientationDegrees` — all four 90°-wide bands, exact boundary values, wraparound, and the `-1` (`ORIENTATION_UNKNOWN`) passthrough.
- No instrumented-test or migration changes are needed (no schema/DB change in this plan).
- No e2e framework in this project; the manifest orientation lock and the `MainActivity`/`PhotoCaptureScreen` Compose/Android-adapter changes are verified manually (Post-Completion) and are untested by convention, consistent with the rest of the UI layer.
- `./gradlew lintDebug testDebugUnitTest` must be green before finishing.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Keep the plan in sync with actual work.

## Solution Overview
- **Fix A — app-wide portrait lock**: add `android:screenOrientation="portrait"` to `MainActivity` in the manifest. The OS never rotates the window, so a physical tilt no longer produces any configuration change or Activity recreate — this removes the rotation trigger for bug 2 (and for the separate scan-session bug noted above) at its root, and matches the product decision to drop landscape support entirely.
- **Fix B — defensive `selectedTeamId` guard**: even with rotation locked out, other config changes (multi-window resize, system font-scale change) can still recreate the Activity and reproduce the same transient-`null` blip. Track the last known *real* team id in a `rememberSaveable` var and skip the overlay-reset body unless the team has genuinely changed.
- **Fix C — physical-tilt tracking for JPEG rotation**: once the window is permanently portrait, `previewView.display.rotation` is always `ROTATION_0`, so it can no longer be used to detect a landscape-held shot at all — a `OrientationEventListener`-based tracker becomes the sole source of truth for what the phone was physically doing at shutter time. A pure `bucketOrientationDegrees` function converts the raw sensor reading to one of `{0, 90, 180, 270}`; a small private adapter class feeds it into `imageCapture.targetRotation`.
- Explicitly out of scope (confirmed with user): retroactively re-rotating already-captured sideways photos; any landscape UI support; the `ScanViewModel` refactor from the deleted draft note.

## Technical Details
- `bucketOrientationDegrees(orientationDegrees: Int, previous: Int): Int` — pure, no Android imports, lives as a top-level function in `PhotoCaptureScreen.kt` (mirrors `scaledDimensions` living as a top-level pure function in `PhotoStorage.kt` alongside adapter code):
  ```kotlin
  fun bucketOrientationDegrees(orientationDegrees: Int, previous: Int): Int {
      if (orientationDegrees < 0) return previous
      return when (orientationDegrees) {
          in 45 until 135 -> 270
          in 135 until 225 -> 180
          in 225 until 315 -> 90
          else -> 0
      }
  }
  ```
  `-1` is `OrientationEventListener.ORIENTATION_UNKNOWN` (device flat / sensor unreliable) — returning `previous` unchanged avoids spurious flips.
- `RotationTracker` (private, untested by convention):
  ```kotlin
  private class RotationTracker(context: Context, initialDegrees: Int) : OrientationEventListener(context) {
      var degrees: Int = initialDegrees
          private set
      override fun onOrientationChanged(orientation: Int) {
          degrees = bucketOrientationDegrees(orientation, degrees)
      }
  }
  ```
  Corrected during implementation: `OrientationEventListener` lives in `android.view`, not `android.hardware` as drafted above — imported as `android.view.OrientationEventListener` (verified against the `android-36.1` `android.jar`).
  Seeded with `Surface.ROTATION_0` (0°) rather than reading `previewView.display?.rotation` — at `remember` time the `PreviewView` isn't attached yet (it's wired up later via `AndroidView`), so that read is almost always `null` anyway, and `display.rotation` uses the inverse sign convention from `OrientationEventListener`'s raw degrees, which is a needless sign-inversion trap for a seed that's overwritten by the first real sensor callback well before the user frames a shot. `0` is a plain, honest "no reading yet" default. Enabled/disabled in a `DisposableEffect(Unit)` guarded by `canDetectOrientation()`, mirroring the existing camera-provider bind/unbind `DisposableEffect` already in this file — on a device with no accelerometer, `degrees` simply stays `0` for the whole session (matches today's fallback behavior in that case).
- `OrientationEventListener`'s default constructor delivers callbacks on the thread that constructed it — main thread here, same as `capture()` — so no `@Volatile`/synchronization is needed (unlike the NFC binder-thread hooks elsewhere in the app).
- `capture()` replaces `imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0` with a read of `rotationTracker.degrees` converted to the matching `Surface.ROTATION_*` constant via a trivial local `when` (not unit-tested — Android-constant plumbing, same status as the rest of the file's camera glue).
- `MainActivity.kt` guard:
  ```kotlin
  var lastRealTeamId by rememberSaveable { mutableStateOf(selectedTeamId) }
  LaunchedEffect(selectedTeamId) {
      if (selectedTeamId == null || selectedTeamId == lastRealTeamId) return@LaunchedEffect
      lastRealTeamId = selectedTeamId
      bindSlot = null; unbindSlot = null; showAdmin = false; showProvisioning = false; showCheckChip = false
      showPhotoPicker = false; photoCaptureMarkId = null; photoCaptureAttach = false
      photoCaptureCpNumber = 0; photoCaptureCheckpointId = 0
      showClearTrackDialog = false; showLocationDisabledDialog = false; showLocationDeniedDialog = false
      val recording = container.trackRecordingState.value as? TrackState.Recording
      if (recording != null && recording.teamId != selectedTeamId) {
          TrackRecordingService.stop(context)
      }
  }
  ```
  The existing recording-stop check drops its own `selectedTeamId != null` guard since the early return above already excludes `null`; behavior is otherwise unchanged, it now just runs strictly less often (only on a genuine team change).

## What Goes Where
- **Implementation Steps** (`[ ]`): manifest change, `MainActivity` guard, pure rotation-bucketing function + its test, `PhotoCaptureScreen` wiring, docs update.
- **Post-Completion** (no checkboxes): manual on-device verification of both bugs (requires physically rotating a phone / toggling auto-rotate — not something a unit test or emulator config can exercise).

## Implementation Steps

### Task 1: Lock the app to portrait

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [x] Add `android:screenOrientation="portrait"` to the `<activity android:name=".MainActivity">` element (around line 51-56).
- [x] Confirm `./gradlew assembleDebug` still builds.
- [x] No automated test (manifest attribute; verified manually in Post-Completion).

### Task 2: Guard the team-switch overlay-reset effect against transient recreation

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] Add `var lastRealTeamId by rememberSaveable { mutableStateOf(selectedTeamId) }` immediately above the existing `LaunchedEffect(selectedTeamId)` (line 725).
- [x] Add the early-return guard (`if (selectedTeamId == null || selectedTeamId == lastRealTeamId) return@LaunchedEffect`) and `lastRealTeamId = selectedTeamId` as the first lines of the effect body; drop the now-redundant `selectedTeamId != null` clause from the recording-stop `if` further down (the early return already excludes `null`).
- [x] Update the comment above the effect (lines 722-724, and the transient-blip comment at 730-732) to describe the new guard and why it exists (protects against a genuine transient-null recreate, not just the recording-stop case).
- [x] Update the inline comment at `MainActivity.kt:1870-1871` ("The camera overlay is normally closed by `LaunchedEffect(selectedTeamId)` before a team deselection reaches this point") — with the new guard, a genuine deselection to `null` no longer auto-closes the overlay either (the effect body only runs on a real team-to-team change), so this branch's reachability reasoning should note that explicitly rather than implying the overlay is always pre-closed.
- [x] No new automated test (Compose host wiring in `MainActivity`, untested by convention like the rest of the file); manually verified in Post-Completion by forcing a config change (e.g. a font-scale change in system settings, since orientation itself is now locked) while `photoCaptureMarkId` is set, confirming the overlay stays open. (skipped here - not automatable, deferred to Post-Completion manual verification)

### Task 3: Pure orientation-bucketing function + tests

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/photo/PhotoCaptureScreen.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/photo/PhotoCaptureScreenTest.kt`

- [x] Add the top-level `fun bucketOrientationDegrees(orientationDegrees: Int, previous: Int): Int` (as specified in Technical Details) to `PhotoCaptureScreen.kt`, kept free of any Android imports in its own signature/body.
- [x] Write `PhotoCaptureScreenTest` covering: each of the four bands (e.g. `10→0`, `90→270`, `180→180`, `270→90`), the exact boundary values (`44`→one bucket, `45`→the next; same for `134/135`, `224/225`, `314/315`), wraparound near `0`/`359` (e.g. `359→0`, `0→0`), and `-1` returning `previous` unchanged (including when `previous` is a non-zero bucket, to prove it's not silently defaulting to `0`).
- [x] Run `./gradlew testDebugUnitTest` — must pass before next task.

### Task 4: Wire physical-tilt tracking into the capture screen

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/photo/PhotoCaptureScreen.kt`

- [x] Add the private `RotationTracker` class (as specified in Technical Details), importing `android.view.OrientationEventListener` (corrected from the plan's draft `android.hardware` package — see Technical Details note).
- [x] In the composable, `remember` a `RotationTracker` seeded with `0` (see Technical Details for why this is preferred over reading `previewView.display?.rotation` at seed time).
- [x] Add a `DisposableEffect(Unit)` that calls `rotationTracker.enable()` only when `rotationTracker.canDetectOrientation()` is true, and `rotationTracker.disable()` in `onDispose` — placed near the existing camera-provider `DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }`.
- [x] In `capture()`, replace `imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0` with a read of `rotationTracker.degrees` converted to the matching `Surface.ROTATION_*` constant via a small local `when`.
- [x] No new test for the wiring itself (Android adapter, untested by convention — only `bucketOrientationDegrees` from Task 3 is tested); confirm `./gradlew assembleDebug` builds.

### Task 5: Verify acceptance criteria

- [ ] Verify `MainActivity` no longer rotates the window at all (manifest lock in place) and `assembleDebug` succeeds.
- [ ] Verify the `lastRealTeamId` guard compiles and the overlay-reset effect only fires its body on an actual team-id change (trace through: cold start with a real team already selected → no reset; genuine team switch → reset fires once).
- [ ] Verify `bucketOrientationDegrees` test coverage from Task 3 passes and covers all boundaries.
- [ ] Verify `capture()` in `PhotoCaptureScreen.kt` no longer reads `previewView.display?.rotation` directly for `targetRotation`.
- [ ] Run full unit suite: `./gradlew lintDebug testDebugUnitTest`.

### Task 6: [Final] Update documentation
- [ ] Update `CLAUDE.md`: note the app is now portrait-locked (`android:screenOrientation="portrait"` on `MainActivity`), describe the `lastRealTeamId` guard on the team-switch reset effect and why it exists, and document `PhotoCaptureScreen.kt`'s new `bucketOrientationDegrees` (JVM-tested) + `RotationTracker` (untested adapter) replacing the old `previewView.display?.rotation` read for `imageCapture.targetRotation`.
- [ ] Move this plan to `docs/plans/completed/`.

## Post-Completion
*Items requiring manual intervention — no checkboxes, informational only.*

**Manual verification (requires a physical device):**
- With auto-rotate **off**, shoot a photo-mark frame while holding the phone in landscape (both left and right side down) — confirm the saved frame (visible in the thumbnail strip / lightbox) is upright, not sideways.
- With auto-rotate **on**, confirm the app no longer visually rotates at all (window stays portrait) when the phone is turned, on every tab and overlay, not just the photo screen.
- Scan an NFC checkpoint to start a take, then physically rotate the phone mid-scan — confirm the scan overlay and its countdown are unaffected (this was the symptom the deleted `fix-rotate-bug.md` draft described; the portrait lock closes the rotation-triggered path, though other recreate paths like font-scale/multi-window/process-death still reset the in-progress countdown UI — acceptable since the take's scoring is already persisted to Room regardless).
- Open the photo-capture overlay, trigger a non-orientation config change if practical (e.g. system font-scale change) — confirm the overlay stays open (validates the Task 2 guard for the residual recreate cases the portrait lock doesn't cover).

**Design/product sign-off:**
- Confirm there's no remaining requirement for landscape support anywhere else in the app (e.g. a future track-map screen) before treating this as a permanent architectural decision.
