package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Snapshot of one team member captured at the moment of a checkpoint take — the source for the
 * `present[]` array in the marks upload contract. Parallel to [MarkEntity.present] (which stays the
 * scoring truth): [numberInTeam] is the slot, [nfcUid] is the bracelet uid read at scan time (may be
 * null), [number] is the global participant number (from the chip binding), and [code] is a per-member
 * chip code placeholder (brackets carry none yet).
 *
 * **`@Serializable` is mandatory** — [MarkMemberSnapshotListConverter] serializes this through
 * kotlinx.serialization; without the annotation `Json.encodeToString` fails to compile.
 */
@Serializable
data class MarkMemberSnapshot(
    val numberInTeam: Int,
    val nfcUid: String?,
    val number: Int,
    val code: String? = null,
)

/**
 * A **local-only** record of one checkpoint-taking event (взятие КП). The table is designed for a
 * future upload to two servers (local wifi + cloud): the [id] is a client-generated UUID so the two
 * servers can merge databases without key collisions, and `(cpUid, cpCode)` is an anti-cheat log that
 * lets the server reconcile the physical checkpoint tag after the fact.
 *
 * A row is created the moment the КП chip is scanned (so the take survives process death), then
 * [present] accumulates the `numberInTeam` of each member scanned within the rolling window. [complete]
 * (= counts for score) is set once `present` covers the whole roster ([expectedCount]); a partial
 * collect is stored for the server log but not scored. A repeat take of the same checkpoint produces a
 * **new** row (history for the server-side order). Dual upload flags ([uploadedLocal]/[uploadedCloud])
 * are indexed by `raceId` and drained by [ru.kolco24.kolco24.data.MarkRepository]'s batch upload loop.
 */
@Entity(
    tableName = "marks",
    indices = [Index("teamId"), Index("checkpointId"), Index("raceId")],
)
data class MarkEntity(
    @PrimaryKey val id: String,
    val raceId: Int,
    val teamId: Int,
    val checkpointId: Int,
    val checkpointNumber: Int,
    val cost: Int,
    val method: String,
    val cpUid: String,
    val cpCode: String,
    val present: List<Int>,
    /**
     * Per-member snapshots ([MarkMemberSnapshot]) captured at scan time — the source for the upload
     * `present[]` array (`nfc_uid`/`code`/`number`/`number_in_team`). Runs **parallel** to [present]
     * (which stays the scoring truth — `present.size` vs [expectedCount] drives [complete]) and is
     * filled with set-semantics by `numberInTeam` on every bracelet scan. NULL on legacy rows written
     * before this column existed; the upload mapper merges over [present] so no member is ever lost.
     */
    val presentDetails: List<MarkMemberSnapshot>? = null,
    val expectedCount: Int,
    val complete: Boolean,
    val photoPath: String? = null,
    val takenAt: Long,
    val updatedAt: Long,
    val uploadedLocal: Boolean = false,
    val uploadedCloud: Boolean = false,
    /**
     * Per-target flag for the photo-frame drain (Phase 2): flips to true only once **all** of this
     * mark's frames (see [photoPath]) have been accepted by the local (LAN) target. Independent of
     * [uploadedLocal] (metadata) — the frame drain is gated on metadata landing first (metadata-first
     * ordering), so this stays false until [uploadedLocal] is true. Reset to false by `attachPhotos`
     * when new frames are appended (re-queues the drain).
     */
    val photosUploadedLocal: Boolean = false,
    /** Same as [photosUploadedLocal], for the cloud target (see [uploadedCloud]). */
    val photosUploadedCloud: Boolean = false,
    /**
     * Trusted take time (monotonic-anchored server time) — the scoring/order source. NULL on legacy
     * rows and whenever no clock sync has happened (the raw [takenAt] wall time is the fallback).
     */
    val trustedTakenAt: Long? = null,
    /**
     * Monotonic timestamp (`SystemClock.elapsedRealtime()`) at take time. **Nullable**: NULL on legacy
     * rows honestly distinguishes "no data" from a real `0` right after boot. Paired with [bootCount]
     * for forensic Δelapsed reconciliation.
     */
    val elapsedRealtimeAt: Long? = null,
    /**
     * Boot-session id (`Settings.Global.BOOT_COUNT`) of the [elapsedRealtimeAt] mark. Required so a
     * future Δelapsed reconciliation never mixes monotonic marks from different boot sessions (which
     * live on different timelines and would otherwise read as a false jump). NULL on legacy rows or
     * when the boot count could not be read.
     */
    val bootCount: Int? = null,
    /**
     * GPS latitude of the **place** where the КП was taken (anti-cheat / physical-presence proof). A
     * one-shot fresh fix is requested the moment the КП chip is scanned and written here asynchronously
     * (two-phase like [present]), so it works even when track recording is off. NULL = no fix obtained
     * (no permission / GPS off / no provider / timeout); `locLat == null` is the "no coordinate"
     * sentinel. Paired with [locLon].
     */
    val locLat: Double? = null,
    /** GPS longitude of the take place (see [locLat]). NULL together with [locLat] when no fix. */
    val locLon: Double? = null,
    /**
     * Horizontal accuracy in meters (`Location.accuracy`) of the take fix — the **key anti-cheat
     * signal**: it lets the server judge how trustworthy [locLat]/[locLon] are. NULL when no fix.
     */
    val locAccuracy: Float? = null,
    /** Altitude in meters above the WGS84 ellipsoid (`Location.altitude`), or NULL when unavailable. */
    val locAltitude: Double? = null,
    /**
     * Vertical accuracy in meters (`Location.verticalAccuracyMeters`, API 26+) of [locAltitude]. Stored
     * alongside the altitude because without it the altitude is a weak anti-cheat signal. NULL when
     * unavailable.
     */
    val locVerticalAccuracy: Float? = null,
    /** Satellite time of the take fix (`Location.time`, ms since epoch). NULL when no fix. */
    val locGpsTimeMs: Long? = null,
    /**
     * Monotonic moment of the take fix (`Location.elapsedRealtimeNanos / 1_000_000`). Compared against
     * the take's own [elapsedRealtimeAt] it gives the **age of the fix** (Δ) at take time — a key
     * anti-cheat signal (a stale fix is suspect). NULL when no fix.
     */
    val locElapsedRealtimeAt: Long? = null,
)
