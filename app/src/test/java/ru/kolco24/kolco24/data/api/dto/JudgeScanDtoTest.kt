package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.JudgeScanEntity

class JudgeScanDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun scan(
        trustedTakenAt: Long? = 1_718_900_000_123L,
        bootCount: Int? = 7,
    ) = JudgeScanEntity(
        id = "scan-1",
        raceId = 8,
        eventType = "start",
        participantNumber = 101,
        nfcUid = "04A2B3C4D5E680",
        takenAt = 1_718_900_000_000L,
        trustedTakenAt = trustedTakenAt,
        elapsedRealtimeAt = 9_876_543L,
        bootCount = bootCount,
        sourceInstallId = "install-uuid",
    )

    @Test
    fun toDto_mapsAllFields() {
        val dto = scan().toDto()

        assertEquals("scan-1", dto.id)
        assertEquals("start", dto.eventType)
        assertEquals(101, dto.participantNumber)
        assertEquals("04A2B3C4D5E680", dto.nfcUid)
        assertEquals(1_718_900_000_000L, dto.wallMs)
        assertEquals(1_718_900_000_123L, dto.trustedMs)
        assertEquals(9_876_543L, dto.elapsedAt)
        assertEquals(7, dto.bootCount)
    }

    @Test
    fun toDto_nullTrustedTakenAt_mapsToNullTrustedMs() {
        val dto = scan(trustedTakenAt = null).toDto()

        assertNull(dto.trustedMs)
        // wall_ms always present (the fallback when trusted_ms is null)
        assertEquals(1_718_900_000_000L, dto.wallMs)
    }

    @Test
    fun toDto_nullBootCount_mapsToNullBootCount() {
        val dto = scan(bootCount = null).toDto()

        assertNull(dto.bootCount)
    }

    @Test
    fun serialization_emitsSnakeCaseKeys() {
        val request = JudgeScanUploadRequest(
            sourceInstallId = "install-uuid",
            scans = listOf(scan().toDto()),
        )

        val encoded = json.encodeToString(JudgeScanUploadRequest.serializer(), request)

        assertTrue(encoded.contains("\"source_install_id\""))
        assertTrue(encoded.contains("\"event_type\""))
        assertTrue(encoded.contains("\"participant_number\""))
        assertTrue(encoded.contains("\"nfc_uid\""))
        assertTrue(encoded.contains("\"wall_ms\""))
        assertTrue(encoded.contains("\"trusted_ms\""))
        assertTrue(encoded.contains("\"elapsed_at\""))
        assertTrue(encoded.contains("\"boot_count\""))
    }

    @Test
    fun serialization_nullTrustedMs_roundTrips() {
        val dto = scan(trustedTakenAt = null).toDto()

        val encoded = json.encodeToString(JudgeScanDto.serializer(), dto)
        val decoded = json.decodeFromString(JudgeScanDto.serializer(), encoded)

        assertNull(decoded.trustedMs)
        assertEquals(dto, decoded)
    }

    @Test
    fun deserialization_parsesResponseAccepted() {
        val payload = """{"accepted": ["scan-1", "scan-2"]}"""

        val response = json.decodeFromString(JudgeScanUploadResponse.serializer(), payload)

        assertEquals(listOf("scan-1", "scan-2"), response.accepted)
    }
}
