package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level payload of `GET /app/race/<race_id>/member_tags/` (see docs/API.md). */
@Serializable
data class MemberTagsResponse(
    @SerialName("member_tags") val memberTags: List<MemberTagDto>,
)

/**
 * One slot in the race's NFC chip pool: a physical chip's normalized UID mapped to the
 * participant `number` it is assigned to. There is no server-side `id` — a slot is identified by
 * its `nfc_uid` (already trimmed + UPPERCASE server-side).
 */
@Serializable
data class MemberTagDto(
    val number: Int,
    @SerialName("nfc_uid") val nfcUid: String,
)
