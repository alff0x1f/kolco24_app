package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body of `POST /app/login/` (see docs/mobile-admin-auth-and-tags.md). */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

/**
 * Response of a successful `POST /app/login/`: the opaque 30-day bearer `token` and its
 * `expires_at` ISO timestamp (UTC, `Z` suffix, e.g. `2026-07-21T14:03:00Z`).
 */
@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
)
