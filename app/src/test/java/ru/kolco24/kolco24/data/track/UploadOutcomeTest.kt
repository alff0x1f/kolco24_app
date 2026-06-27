package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.api.PostResult

class UploadOutcomeTest {

    @Test
    fun success_mapsToOk() {
        assertEquals(UploadResultKind.Ok, uploadResultKind(PostResult.Success("anything")))
    }

    @Test
    fun offline_mapsToOffline() {
        assertEquals(UploadResultKind.Offline, uploadResultKind(PostResult.Offline))
    }

    @Test
    fun forbidden_mapsToError() {
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.Forbidden))
    }

    @Test
    fun otherFailures_mapToError() {
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.Error(500)))
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.BadRequest))
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.Unauthorized))
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.Conflict))
        assertEquals(UploadResultKind.Error, uploadResultKind(PostResult.RateLimited))
    }
}
