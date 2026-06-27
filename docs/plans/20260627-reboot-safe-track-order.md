# Reboot-safe GPS track ordering

## Summary

GPS track export and display currently use `elapsedRealtimeAt` as the global point order. Android
resets that monotonic clock after a device reboot, so points recorded after reboot can be sorted into
the middle of an older track. GPX export then sees alternating `segmentId`s and splits the track into
hundreds of tiny `<trkseg>` blocks.

Fix by using a single reboot-safe order everywhere track points are read, displayed, uploaded, or
exported: `trustedMs ?: wallMs`, then `bootCount`, then `elapsedRealtimeAt`, then `id`.

## Key Changes

- Add a shared JVM-testable track point comparator in the pure track model layer.
- Extend `TrackPointLike` with the fields needed by the comparator: `id`, `bootCount`, `wallMs`, and
  `trustedMs`.
- Replace direct `elapsedRealtimeAt` sorting in UI/export and test fake DAO with the shared helper.
- Update Room queries and `scripts/dump-track.sh` to use the same SQL order:
  `COALESCE(trustedMs, wallMs), COALESCE(bootCount, -1), elapsedRealtimeAt, id`.
- Keep `buildGpx()` simple: callers pass filtered, correctly ordered points; the serializer only emits
  one `<trkseg>` per consecutive `segmentId`.

## Test Plan

- Add a comparator regression test for a reboot case where the newer point has a smaller
  `elapsedRealtimeAt` but a later `wallMs`.
- Update `TrackRepositoryTest` fake DAO ordering to match production ordering.
- Add a GPX regression test proving caller-side reboot-safe sorting avoids alternating one-point
  segments.
- Run:

```bash
./gradlew testDebugUnitTest --tests '*Track*'
./gradlew testDebugUnitTest
```

## Assumptions

- No schema migration is needed; this changes read/export/upload order only.
- `trustedMs ?: wallMs` is the canonical local ordering for display and GPX export.
- `elapsedRealtimeAt` remains a tie-breaker within one boot session, not a global ordering key.
- Repairing already exported GPX files is a separate one-off task.
