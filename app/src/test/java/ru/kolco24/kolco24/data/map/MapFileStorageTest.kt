package ru.kolco24.kolco24.data.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private var now = 1_000L

    @Before
    fun setUp() {
        root = File(tmp.root, "maps")
        storage = MapFileStorage(root, clock = { now })
        storage.ensureRoot()
    }

    private fun commit(raceId: Int, text: String): File {
        storage.partFile(raceId).writeText(text)
        assertTrue(storage.commit(raceId))
        return storage.file(raceId)!!
    }

    @Test
    fun pathScheme() {
        assertEquals(File(root, "7.mbtiles.part"), storage.partFile(7))
        assertEquals(File(root, "7-1000.mbtiles"), commit(7, "m"))
    }

    @Test
    fun existsSizeDelete() {
        assertNull(storage.file(3))
        assertNull(storage.size(3))
        storage.partFile(3).writeBytes(ByteArray(42))
        assertTrue(storage.commit(3))
        assertEquals(42L, storage.size(3))
        storage.delete(3)
        assertNull(storage.file(3))
        assertNull(storage.size(3))
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test
    fun deleteMissingIsNoOpSuccess() {
        storage.delete(99)
        assertNull(storage.file(99))
        assertEquals(emptyMap<Int, File>(), storage.listDownloaded())
    }

    @Test
    fun deleteRemovesEveryGenerationAndPart() {
        File(root, "4-5.mbtiles").writeText("old")
        File(root, "4-9.mbtiles").writeText("new")
        storage.partFile(4).writeText("partial")
        File(root, "40-1.mbtiles").writeText("other race")
        storage.delete(4)
        assertNull(storage.file(4))
        assertEquals(listOf("40-1.mbtiles"), root.list()!!.toList())
    }

    @Test
    fun listDownloadedIgnoresPartsAndForeignFiles() {
        val one = commit(1, "a")
        val twentyTwo = commit(22, "b")
        storage.partFile(5).writeText("partial")
        File(root, "notes.txt").writeText("x")
        File(root, "abc.mbtiles").writeText("x")
        File(root, "3.mbtiles").writeText("x") // no generation
        File(root, "3-x.mbtiles").writeText("x")
        File(root, "3-1-2.mbtiles").writeText("x")
        File(root, "mbgl-offline.db").writeText("x")
        File(root, "9-1.mbtiles").mkdirs() // a directory, not a map
        assertEquals(mapOf(1 to one, 22 to twentyTwo), storage.listDownloaded())
    }

    @Test
    fun listDownloadedPicksNewestGeneration() {
        File(root, "4-5.mbtiles").writeText("old")
        File(root, "4-12.mbtiles").writeText("new")
        assertEquals(mapOf(4 to File(root, "4-12.mbtiles")), storage.listDownloaded())
        assertEquals("new", storage.file(4)!!.readText())
    }

    @Test
    fun listDownloadedMissingRootIsEmpty() {
        assertEquals(emptyMap<Int, File>(), MapFileStorage(File(tmp.root, "absent")).listDownloaded())
    }

    @Test
    fun commitPromotesPart() {
        storage.partFile(4).writeText("new")
        assertTrue(storage.commit(4))
        assertFalse(storage.partFile(4).exists())
        assertEquals("new", storage.file(4)!!.readText())
    }

    @Test
    fun commitMovesToFreshPathAndPrunesOld() {
        val old = commit(4, "old")
        val new = commit(4, "new") // same clock: generation still strictly increases
        assertNotEquals(old, new)
        assertFalse(old.exists())
        assertEquals("new", new.readText())
        assertFalse(storage.partFile(4).exists())
        assertEquals(mapOf(4 to new), storage.listDownloaded())
    }

    @Test
    fun pathIsNeverReusedAfterDeleteEvenIfClockGoesBack() {
        val first = commit(4, "a")
        storage.delete(4)
        assertNull(storage.file(4))
        now = 1L
        val second = commit(4, "b")
        assertNotEquals(first, second)
    }

    @Test
    fun generationFollowsClock() {
        now = 5_000L
        assertEquals(File(root, "4-5000.mbtiles"), commit(4, "a"))
    }

    @Test
    fun commitWithoutPartReturnsFalseAndKeepsOld() {
        val old = commit(4, "old")
        assertFalse(storage.commit(4))
        assertEquals(old, storage.file(4))
        assertEquals("old", old.readText())
    }

    @Test
    fun sweepDeletesPartsAndSupersededGenerations() {
        File(root, "1-5.mbtiles").writeText("superseded")
        File(root, "1-9.mbtiles").writeText("map")
        File(root, "2-3.mbtiles").writeText("only")
        storage.partFile(1).writeText("partial")
        storage.partFile(2).writeText("partial")
        File(root, "other.txt").writeText("x")
        storage.sweep()
        assertEquals(
            setOf("1-9.mbtiles", "2-3.mbtiles", "other.txt"),
            root.list()!!.toSet(),
        )
    }

    @Test
    fun sweepMissingRootIsNoOp() {
        val absent = File(tmp.root, "absent")
        MapFileStorage(absent).sweep()
        assertFalse(absent.exists())
    }

    @Test
    fun commitOntoUnrenamableTargetReturnsFalseAndKeepsOld() {
        val old = commit(4, "old")
        // A non-empty directory where the next generation should go: rename(2) fails.
        val target = storage.generationFile(4, 1_001L)
        target.mkdirs()
        File(target, "keep").writeText("x")
        storage.partFile(4).writeText("new")
        assertFalse(storage.commit(4))
        assertTrue(File(target, "keep").exists())
        assertEquals("old", storage.file(4)!!.readText())
        assertEquals(old, storage.file(4))
    }
}
