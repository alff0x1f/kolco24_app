package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body of `POST /app/race/<race_id>/member_tags/bind/` — get the secret code for bracelet
 * [nfcUid]. [number] `null` means "the UID is already in the pool, the server knows the participant"
 * (an unknown UID → `404`); a number creates the binding. `number` has no default, so it is always
 * encoded — `null` as an explicit JSON `null`.
 */
@Serializable
data class MemberTagBindRequest(
    @SerialName("nfc_uid") val nfcUid: String,
    val number: Int?,
)

/**
 * Response of `POST /app/race/<race_id>/member_tags/bind/` (201 on a new tag, 200 on an idempotent
 * repeat — same code): the participant `number`, the normalized `nfc_uid`, and the hex `code` to
 * write onto the bracelet as a `K24` participant record.
 */
@Serializable
data class MemberTagBindResponse(
    val number: Int,
    @SerialName("nfc_uid") val nfcUid: String,
    val code: String,
)
