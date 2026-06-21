package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body of `POST /app/race/<race_id>/tags/` — bind one physical chip UID to a checkpoint. */
@Serializable
data class TagBindRequest(
    @SerialName("checkpoint_id") val checkpointId: Int,
    @SerialName("nfc_uid") val nfcUid: String,
)

/**
 * Response of `POST /app/race/<race_id>/tags/` (201 on a fresh bind, 200 on an idempotent re-bind):
 * the bound tag's `bid`, its `checkpoint_id`, the human-readable checkpoint `number`, the normalized
 * `nfc_uid`, and the hex `code` to write onto the chip so the app can recognise the КП offline.
 */
@Serializable
data class TagBindResponse(
    val bid: String,
    @SerialName("checkpoint_id") val checkpointId: Int,
    val number: Int,
    @SerialName("nfc_uid") val nfcUid: String,
    val code: String,
)
