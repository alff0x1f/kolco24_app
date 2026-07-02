# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
./gradlew assembleDebug              # build debug APK
./gradlew assembleRelease            # build release APK
./gradlew lintDebug                  # run lint (must pass before merging)
./gradlew testDebugUnitTest          # run unit tests
./gradlew connectedDebugAndroidTest  # run instrumented tests (requires emulator/device) — guards CheckpointDao preserve-on-resync + MIGRATION_1_2/MIGRATION_2_3/MIGRATION_3_4/MIGRATION_4_5 + MarkDaoTest frame-drain queries + attachPhotos column-scope + uploadCountsMetadata/photoFrameRows + JudgeScanDaoTest
```

## Architecture

Single-activity Jetpack Compose app (minSdk 24, targetSdk 36). No ViewModel, no Navigation Compose — screen state lives in `MainActivity.kt` via `rememberSaveable`. The data layer syncs races/teams/legend/member-tags via signed `GET` with ETag and uses Room as the single source of truth. NFC reader-mode and GPS-track recording are the two device-integration subsystems.

**Per-file design notes** (the "why" behind each file, its gotchas, test coverage) live in `docs/design/UI-NOTES.md` (MainActivity cross-file wiring + `ui/**`) and `docs/design/DATA-NOTES.md` (`data/**`, `Kolco24App`/`AppContainer`, `TrackRecordingService`). **Read the relevant notes file before modifying those areas, and update it when behavior changes** — the notes replace the old inline UI/Data sections of this file.

## Conventions & gotchas

These cross-cutting rules hold project-wide; the notes files assume them rather than repeating them.

- **minSdk 24, no desugaring** — use `SimpleDateFormat` (`Locale.US`, UTC where needed), never `java.time`. ISO date/time strings are fixed-width, so compare them **lexicographically** instead of parsing.
- **Pure models** (`ScanSession`, `CheckpointColor`, `ThemeMode`, `TrackProfile`, `TrustedClock`, `ProvisioningModel`, `ChipCheckModel`, `TrackModels`, `GpxExport`, the `*Logic`/mapper helpers, `LegendCrypto`) are **Android-free and JVM-unit-tested** (their `*Test` classes). **Compose UI, real Android adapters** (location engines, `TrackRecordingService`, the `NfcA`/DAO adapters), and trivial wiring are **untested by convention**. Trust boundaries (request signing, `ServerTimeInterceptor`) are the exception — they are tested.
- **Duplicate, don't couple** — small repeated UI rows (e.g. `SwitchTeamRow`/`ChangeTeamRow`, the marks vs track upload-status row) are **copied**, not extracted to a shared file.
- **Room is shipped** (see memory `room-released-with-migrations`) — on-device data must survive upgrades. A schema bump needs a real `Migration` appended to `.addMigrations(...)` **and** a committed `schemas/<n>.json`, or it crashes on upgrade. `exportSchema = true`.
- **Overlay pattern** — full-screen overlays (scan, settings, team-picker, admin, provisioning, check-chip, bind/unbind) are driven by `rememberSaveable` flags rendered after `Scaffold` in one `Box`, dismissed via `BackHandler`. No Navigation Compose. Per-team/race flows use the null-guarded keyed-`remember` pattern: `remember(selectedTeamId) { id?.let { repo.observe(it) } ?: flowOf(emptyList()) }`.
- **Writes outlive overlays** — a write triggered by a closing overlay (select team, bind, persist take) runs on `container.applicationScope`, not composition scope.
- **Repo refresh pattern** — on a `200`, persist rows (`replaceAllForRace`) **then** upsert the ETag as two separate transactions (a crash between them keeps fresh data with a stale ETag and self-heals next sync); the ETag upsert is skipped when the response omits the header.
- **SyncSource routing + persist guard** — the four sync repos take a `source: SyncSource = SyncSource.Cloud` param (default preserves every existing call site); a `Cloud` refresh for a currently-pinned race returns `RefreshResult.Skipped` **both** at method entry (before the network call) **and** again in the `200` branch (an in-flight cloud response must not clobber fresher local rows just-pinned mid-flight). Cloud and LAN ETags are partitioned by origin in `sync_meta`, but the data tables they gate (races/teams/legend/member-tags) are **not** origin-scoped — so every `200` write also deletes the *other* origin's cached ETag for that resource (`SyncMetaDao.deleteEtag`), forcing that origin's next fetch past any 304 that would otherwise skip re-persisting over rows the other origin just wrote. See `data/lease/`, `data/SyncSource.kt`, `data/sync/SyncCoordinator.kt`.
- **Dual-target upload loop** (marks + track + judge scans) — `Mutex.tryLock`-guarded, drains in `LIMIT` batches, marks only `accepted ∩ batch`, scoped by `(raceId, teamId)` (judge scans: `raceId` only — a judge station covers every team), idempotent by client UUID, flushes to both cloud and LAN targets independently.
- **Secrets** — `BuildConfig` fields from `local.properties` keys (env-var fallback for CI); the build fails with a descriptive error if a required key is missing.

## Module map

One line per area — full per-file notes in the two docs files above.

### UI (`MainActivity.kt` + `ui/**` → `docs/design/UI-NOTES.md`)

- `MainActivity.kt` — entry point; `HorizontalPager` tabs, hosts all overlay state, NFC reader-mode dispatch (`@Volatile` tag hooks), open-on-tap, 5-min foreground upload-retry ticker, 60 s judge-scan upload ticker.
- `ui/scan/` — NFC take overlay (`ScanScreen`, 20 s window, completion hold) + pure `ScanSession` state machine + pure `ScanFeedback` mapper.
- `ui/marks/MarksScreen.kt` — Отметки tab: tile grid, metrics, photo tiles + lightbox, judge-review and hidden-КП notices, take celebration.
- `ui/legend/` — Легенда tab (locked-row masking, team-scoped taken, ScoreCard scoring counts) + pure `CheckpointColor`.
- `ui/team/` — Команда tab (roster + live chip bindings) + `BindChipSheet` (pure `decideBind`).
- `ui/teampicker/` — team-selection flow + pure `TeamPickerLogic`.
- `ui/photo/` — CameraX capture overlay (pure `bucketOrientationDegrees` rotation) + `PhotoNumberPicker`.
- `ui/admin/` — admin overlay: login, chip provisioning, КП-chip check, member-bracelet check, judge start/finish scan (pure `ProvisioningModel`/`ChipCheckModel`/`MemberChipCheckModel`/`JudgeScanModel`).
- `ui/settings/SettingsScreen.kt` — settings cards: team, theme, track profile, local-mode switch, admin, debug-only tools.
- `ui/upload/` — «Загрузка данных» overlay + pure upload-status models (shared home for `TrackUploadStatus`).
- `ui/track/TrackCard.kt` — track recording card in TeamScreen; GPX share.
- `ui/common/` — `ClockWarningBanner`/`ScanClockBanner`, `PullToRefresh`.
- `ui/theme/` — M3 tokens, `Kolco24Theme` (no dynamic color), pure `ThemeMode`, `RobotoMono` type.

### Data (`data/**` + app entry → `docs/design/DATA-NOTES.md`)

- `Kolco24App.kt` / `AppContainer.kt` — Application launch sequences A/B; manual DI (no Hilt), construction cycles broken via lambda seams.
- `data/api/` — HMAC `AppSignatureInterceptor` (GET-only 403 retry-once), `ApiClient` (cloud + LAN instances share the upload methods), `ServerTimeInterceptor` (trusted-time anchor), snake_case DTOs.
- `data/db/` — Room **v5**, migrations 1→5 with committed schemas; local-only marks/track/bindings/judge-scan tables; DAO gotchas: preserve-on-resync, column-scoped updates, version-guarded flag flips, frame-drain queries.
- `data/lease/` + `data/SyncSource.kt` + `data/sync/SyncCoordinator.kt` — local-mode pin/lease subsystem (race-day LAN switch).
- `data/{Race,Team,Legend,MemberTags}Repository.kt` — the four sync repos (repo refresh pattern + SyncSource routing); `LegendRepository` also owns the offline `unlock` reveal path and legend aggregates.
- `data/MarkRepository.kt` — two-phase takes, dual-target upload loop, photo-mark creation + frame drain, pure metric helpers.
- `data/JudgeScanRepository.kt` — judge start/finish pik log, write-once rows scoped by `raceId` only (no team dimension), dual-target upload loop.
- `data/MemberChipBindingRepository.kt` — local-only member↔chip bindings, atomic reassign; keyed by `(teamId, numberInTeam)`.
- `data/marks/` — pure `PhotoPaths` codec (path-traversal guard, thumb convention), pure `PhotoTarget` router, `PhotoStorage` frame I/O adapter.
- `data/track/` — GPS subsystem: pure models/GPX/profiles, `TrackRepository`, location engines + one-shot `CurrentLocationProvider`.
- `data/nfc/MifareUltralightWriter.kt` — raw `K24` on-chip format; header written **last** (commit marker), `NfcA` direct.
- `data/crypto/LegendCrypto.kt` — pure offline legend crypto (bid / HKDF / AES-GCM), never throws.
- `data/time/TrustedClock.kt` + `ClockAnchorStore` — monotonic+server trusted time, reboot detection.
- `data/ScanFeedbackPlayer.kt` — SoundPool/vibration adapter for scan outcomes + celebration cues (eager-constructed).
- `data/AdminAuthRepository.kt`/`AdminTokenStore.kt`, `ThemePreference`/`TrackProfilePreference`/`InstallId`, pure `NfcUid`, `DateUtils`.
- `TrackRecordingService.kt` — foreground GPS service; lossless «Стоп» flush, live profile switch, 10-min throttled live upload.

## Config / references

- **Secrets** — `local.properties` keys → `BuildConfig` (`API_BASE_URL`/`APP_KEY_ID`/`APP_SECRET`, env fallback for CI, build fails on missing). `LOCAL_API_BASE_URL` is the LAN track/marks target (default `http://192.168.1.5/`). **Host ↔ cleartext coupling:** `res/xml/network_security_config.xml` permits cleartext **only** for `192.168.1.5` — pointing at a different host requires editing the `domain-config` in lockstep.
- **Build** — KSP + `kotlin.plugin.serialization` on AGP 9's built-in Kotlin (do **not** also apply `kotlin.android` — "plugin applied twice"); KSP pinned to `2.2.10-*`; `android.disallowKotlinSourceSets=false`.
- **Permissions/manifest** — NFC + location/foreground-service/notifications declared; `<uses-feature ... required="false">` for NFC, GPS, **and camera** keeps the app installable on camera-less devices (photo-mark is a fallback, not a hard requirement). `CAMERA` permission declared. `MainActivity` is `singleTop` (harmless leftover) and **portrait-locked** (`android:screenOrientation="portrait"`, a product decision to drop landscape support entirely) — the OS never rotates the window, so a physical tilt produces no configuration change or Activity recreate (see the `lastRealTeamId` guard and `PhotoCaptureScreen`'s `RotationTracker` in the UI notes). `FileProvider` authority `${applicationId}.fileprovider` exposes only `cache/tracks/`. **`android.permission.VIBRATE` must stay in the manifest** or every scan-feedback vibrate is a silent no-op.
- **Dependencies (photo-mark)** — CameraX 1.4.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`), Coil Compose 2.7.0 (`io.coil-kt:coil-compose`). CameraX rotation comes from `imageInfo.rotationDegrees` (baked into pixels on save); no separate ExifInterface needed.
- **Docs** — `docs/design/UI-NOTES.md` / `docs/design/DATA-NOTES.md` (per-file design notes, see above); `docs/API.md` (HMAC signing, ETag/304, 403 debug checklist); `docs/design/API.md` (legend crypto contract); `docs/design/UPLOAD.md` (track/marks/judge-scans upload contracts); `docs/mobile-admin-auth-and-tags.md` (admin auth + tags); `assets/template_v2.html` (hi-fi M3 mockup — screens A1b/A2/A3/A4).
- **M3 tokens** (in `Color.kt`) — `OrangeCta #C65A2E` (FABs, active nav, NFC tint), `tertiary #1F7A3D` (success/taken), `inverseSurface #1D242D` (dark hero cards), `outlineVariant #E2E6EB` (dividers).
- **App assets** — `app/src/main/assets/adi-registration.properties` (ADI registration token, tracked — do not expose the value); `res/drawable/ic_launcher_monochrome.xml` (Android 13+ themed icon, alpha-mask only); `res/raw/` scan-feedback and celebration clips (PCM WAV to avoid MP3 encoder-delay lag; underscore names — aapt2 rejects hyphens): `beep_ok3`/`beep_err` (success/failure), `shutter` (photo click), `checkpoint_mark_completed` (fanfare), `mark_added_mario` (coin); `beep_scan.wav` is unused but kept.
