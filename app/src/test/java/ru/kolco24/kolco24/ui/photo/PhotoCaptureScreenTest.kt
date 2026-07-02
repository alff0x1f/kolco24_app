package ru.kolco24.kolco24.ui.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCaptureScreenTest {

    @Test
    fun bucketOrientationDegreesMapsEachBandToItsBucket() {
        assertEquals(0, bucketOrientationDegrees(10, 999))
        assertEquals(270, bucketOrientationDegrees(90, 999))
        assertEquals(180, bucketOrientationDegrees(180, 999))
        assertEquals(90, bucketOrientationDegrees(270, 999))
    }

    @Test
    fun bucketOrientationDegreesHandlesLowerBoundaries() {
        assertEquals(0, bucketOrientationDegrees(44, 999))
        assertEquals(270, bucketOrientationDegrees(45, 999))
        assertEquals(270, bucketOrientationDegrees(134, 999))
        assertEquals(180, bucketOrientationDegrees(135, 999))
        assertEquals(180, bucketOrientationDegrees(224, 999))
        assertEquals(90, bucketOrientationDegrees(225, 999))
        assertEquals(90, bucketOrientationDegrees(314, 999))
        assertEquals(0, bucketOrientationDegrees(315, 999))
    }

    @Test
    fun bucketOrientationDegreesWrapsAroundZero() {
        assertEquals(0, bucketOrientationDegrees(359, 999))
        assertEquals(0, bucketOrientationDegrees(0, 999))
    }

    @Test
    fun bucketOrientationDegreesReturnsPreviousOnUnknown() {
        assertEquals(999, bucketOrientationDegrees(-1, 999))
        assertEquals(0, bucketOrientationDegrees(-1, 0))
        assertEquals(180, bucketOrientationDegrees(-1, 180))
    }
}
