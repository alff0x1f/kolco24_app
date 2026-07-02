package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPathsTest {

    @Test
    fun roundTripPreservesValuesAndOrder() {
        val paths = listOf(
            "marks/m1/a.jpg",
            "marks/m1/b.jpg",
            "marks/m1/c.jpg",
        )

        val restored = photoPaths(encodePhotoPaths(paths))

        assertEquals(paths, restored)
    }

    @Test
    fun roundTripEmptyList() {
        val restored = photoPaths(encodePhotoPaths(emptyList()))

        assertTrue(restored.isEmpty())
    }

    @Test
    fun nullAndBlankDecodeToEmpty() {
        assertTrue(photoPaths(null).isEmpty())
        assertTrue(photoPaths("").isEmpty())
        assertTrue(photoPaths("   ").isEmpty())
    }

    @Test
    fun malformedJsonDecodesToEmpty() {
        assertTrue(photoPaths("not-json").isEmpty())
        assertTrue(photoPaths("{\"key\":\"value\"}").isEmpty())
        assertTrue(photoPaths("[1,2,3]").isEmpty())
    }

    @Test
    fun absolutePathsAreDropped() {
        val restored = photoPaths(
            encodePhotoPaths(listOf("/data/data/app/marks/m1/a.jpg", "marks/m1/b.jpg")),
        )

        assertEquals(listOf("marks/m1/b.jpg"), restored)
    }

    @Test
    fun traversalPathsAreDropped() {
        val restored = photoPaths(
            encodePhotoPaths(
                listOf(
                    "marks/../../etc/passwd",
                    "marks/m1/../secret.jpg",
                    "marks/m1/ok.jpg",
                ),
            ),
        )

        assertEquals(listOf("marks/m1/ok.jpg"), restored)
    }

    @Test
    fun wrongShapeEntriesAreDropped() {
        val restored = photoPaths(
            encodePhotoPaths(
                listOf(
                    "other/m1/a.jpg", // wrong root
                    "marks/m1/a.png", // wrong extension
                    "marks/m1", // too few segments
                    "marks/m1/sub/a.jpg", // too many segments
                    "marks//a.jpg", // blank segment
                    "marks/m1/a.jpg", // the only valid one
                ),
            ),
        )

        assertEquals(listOf("marks/m1/a.jpg"), restored)
    }

    @Test
    fun frameIdOfValidPathReturnsUuidStem() {
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            frameIdOf("marks/m1/550e8400-e29b-41d4-a716-446655440000.jpg"),
        )
    }

    @Test
    fun thumbPathOfRelativeFramePath() {
        assertEquals(
            "marks/m1/550e8400-e29b-41d4-a716-446655440000.thumb.jpg",
            thumbPathOf("marks/m1/550e8400-e29b-41d4-a716-446655440000.jpg"),
        )
    }

    @Test
    fun thumbPathOfBareFileName() {
        // The write site passes just the frame's file name (the dir is already resolved).
        assertEquals("a.thumb.jpg", thumbPathOf("a.jpg"))
    }

    @Test
    fun thumbPathOfStaysUnderTheFrameDirectory() {
        // The derived path only ever rewrites the extension — a validated safe frame path can never
        // produce a thumb path escaping marks/<markId>/ (deletePhoto relies on this).
        assertTrue(isSafeRelativePhotoPath(thumbPathOf("marks/m1/a.jpg")))
    }

    @Test
    fun frameIdOfDefensiveCases() {
        // No extension: only the trailing ".jpg" suffix is stripped, so an extensionless name is
        // returned unchanged rather than throwing.
        assertEquals("noext", frameIdOf("marks/m1/noext"))
        // Nested path: only the last segment matters.
        assertEquals("a", frameIdOf("marks/m1/sub/a.jpg"))
        // Bare filename, no directory at all.
        assertEquals("a", frameIdOf("a.jpg"))
    }
}
