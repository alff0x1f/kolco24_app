package ru.kolco24.kolco24.data.marks

import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.track.UploadTarget

/**
 * How a checkpoint take must be verified before it counts (server tag field `check_method`,
 * snapshotted onto [MarkEntity.checkMethod] at scan time):
 * - [Offline] — a complete take counts (legacy behavior).
 * - [Cloud] — counts only once the **cloud** server accepted the mark while the scan overlay was open.
 * - [Local] — the same, through the **local (LAN)** server.
 *
 * Pure / Android-free; unknown or missing values parse to [Offline] (backward compatibility).
 */
enum class CheckMethod {
    Offline, Cloud, Local;

    /** The server/DB string for this method (`marks.checkMethod`); round-trips through [parse]. */
    val wire: String
        get() = name.lowercase()

    /** The server that must confirm the take, or null for [Offline] (no confirmation needed). */
    val uploadTarget: UploadTarget?
        get() = when (this) {
            Cloud -> UploadTarget.Cloud
            Local -> UploadTarget.Local
            Offline -> null
        }

    companion object {
        fun parse(raw: String?): CheckMethod = when (raw) {
            "cloud" -> Cloud
            "local" -> Local
            else -> Offline
        }
    }
}

/**
 * Scoring rule: a take counts when it is [MarkEntity.complete] **and** either needs no server
 * confirmation ([CheckMethod.Offline]) or was confirmed from the open scan overlay
 * ([MarkEntity.confirmedAt] set). Replaces the bare `complete` check in every "taken"/score derivation.
 */
fun MarkEntity.isCounted(): Boolean =
    complete && (CheckMethod.parse(checkMethod) == CheckMethod.Offline || confirmedAt != null)

/**
 * A complete cloud/local take the server has not confirmed — shown (dimmed tile + notice) but not
 * counted. Partial takes are never "unconfirmed" (they don't count regardless).
 */
fun MarkEntity.isUnconfirmed(): Boolean =
    complete && CheckMethod.parse(checkMethod) != CheckMethod.Offline && confirmedAt == null
