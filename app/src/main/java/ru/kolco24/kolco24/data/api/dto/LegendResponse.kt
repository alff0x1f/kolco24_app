package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level payload of `GET /app/race/<race_id>/legend/` (see docs/design/API.md). */
@Serializable
data class LegendResponse(
    val race: Int,
    val checkpoints: List<CheckpointDto>,
    val tags: List<TagDto> = emptyList(),
)

/**
 * A single legend checkpoint. Fields are flat (no snake_case), so no `@SerialName` is needed.
 * `type` ∈ `start|finish|test|kp` is kept as a plain string (forward-compatible — unknown types
 * won't break parsing, and there is no per-type styling yet).
 *
 * **Locked** checkpoints (`is_legend_locked` on the server) arrive without `cost`/`description` —
 * the plaintext never leaves the server — and instead carry an `enc` envelope. So `cost` and
 * `description` are nullable, and `enc != null` is the locked sentinel.
 *
 * [color] is **public** (a named semantic token: `""`/`red`/`blue`/`green`/`yellow`/`orange`/`purple`)
 * and is present in **both** the open and locked branches — it is never hidden behind `enc`. It
 * defaults to `null` so that a payload omitting the field is parsed safely; `LegendRepository`
 * coerces `null` to `""` (no bar) via `?: ""` in `CheckpointDto.toEntity`.
 */
@Serializable
data class CheckpointDto(
    val id: Int,
    val number: Int,
    val cost: Int? = null,
    val type: String,
    val description: String? = null,
    val enc: EncDto? = null,
    val color: String? = null,
)

/** AES-256-GCM envelope: `iv` (12 bytes, Base64) + `ct` (`ciphertext || tag(16)`, Base64). */
@Serializable
data class EncDto(
    val iv: String,
    val ct: String,
)

/**
 * One physical NFC tag. `bid` (`sha256(code)[:16]`) maps a scanned tag → the CP id it belongs to
 * (`checkpoint_id`, renamed from `point` on the server). `iv`/`ct` hold the `bundle_blob` envelope
 * for tags that unlock locked CPs; they are `null` for tags of open CPs (identification only).
 */
@Serializable
data class TagDto(
    val bid: String,
    @SerialName("checkpoint_id") val point: Int,
    @SerialName("check_method") val checkMethod: String,
    val iv: String? = null,
    val ct: String? = null,
)
