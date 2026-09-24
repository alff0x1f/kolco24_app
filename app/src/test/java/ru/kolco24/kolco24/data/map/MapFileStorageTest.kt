package ru.kolco24.kolco24.data.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapFileStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var storage: MapFileStorage

    @Before
    fun setUp() {
        root = File(tmp.root, "maps")
        storage = MapFileStorage(root)
        storage.ensureRoot()
    }

    @Test
    fun pathScheme() {
        assertEquals(File(root, "7.mbtiles"), storage.file(7))
        assertEquals(File(root, "7.mbtiles.part"), storage.partFile(7))
    }

    @Test
    fun existsSizeDelete() {
        assertFalse(storage.exists(3))
        assertNull(storage.size(3))
        storage.file(3).writeBytes(ByteArray(42))
        assertTrue(storage.exists(3))
        assertEquals(42L, storage.size(3))
        assertTrue(storage.delete(3))
        assertFalse(storage.exists(3))
        assertNull(storage.size(3))
    }

    @Test
    fun deleteMissingIsNoOpSuccess() {
        assertTrue(storage.delete(99))
    }

    @Test
    fun listDownloadedIgnoresPartsAndForeignFiles() {
        storage.file(1).writeText("a")
        storage.file(22).writeText("b")
        storage.partFile(5).writeText("partial")
        File(root, "notes.txt").writeText("x")
        File(root, "abc.mbtiles").writeText("x")
        File(root, "mbgl-offline.db").writeText("x")
        File(root, "9.mbtiles").mkdirs() // a directory, not a map
        assertEquals(setOf(1, 22), storage.listDownloaded())
    }

    @Test
    fun listDownloadedMissingRootIsEmpty() {
        assertEquals(emptySet<Int>(), MapFileStorage(File(tmp.root, "absent")).listDownloaded())
    }

    @Test
    fun commitPromotesPart() {
        storage.partFile(4).writeText("new")
        assertTrue(storage.commit(4))
        assertFalse(storage.partFile(4).exists())
        assertEquals("new", storage.file(4).readText())
    }

    @Test
    fun commitReplacesExistingFile() {
        storage.file(4).writeText("old")
        storage.partFile(4).writeText("new")
        assertTrue(storage.commit(4))
        assertEquals("new", storage.file(4).readText())
        assertFalse(storage.partFile(4).exists())
        assertEquals(setOf(4), storage.listDownloaded())
    }

    @Test
    fun commitWithoutPartReturnsFalseAndKeepsOld() {
        storage.file(4).writeText("old")
        assertFalse(storage.commit(4))
        assertEquals("old", storage.file(4).readText())
    }

    @Test
    fun sweepPartsDeletesOnlyParts() {
        storage.file(1).writeText("map")
        storage.partFile(1).writeText("partial")
        storage.partFile(2).writeText("partial")
        File(root, "other.txt").writeText("x")
        storage.sweepParts()
        assertTrue(storage.file(1).exists())
        assertFalse(storage.partFile(1).exists())
        assertFalse(storage.partFile(2).exists())
        assertTrue(File(root, "other.txt").exists())
    }

    @Test
    fun sweepPartsMissingRootIsNoOp() {
        MapFileStorage(File(tmp.root, "absent")).sweepParts()
    }
}
