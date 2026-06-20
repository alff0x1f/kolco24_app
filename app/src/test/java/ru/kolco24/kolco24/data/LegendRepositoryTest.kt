package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.ByteString.Companion.toByteString
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.crypto.EncBlob
import ru.kolco24.kolco24.data.crypto.LegendCrypto
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity
import ru.kolco24.kolco24.data.db.TagDao
import ru.kolco24.kolco24.data.db.TagEntity
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LegendRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var checkpointDao: FakeCheckpointDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var syncMetaDao: FakeLegendSyncMetaDao
    private lateinit var repository: LegendRepository
    private lateinit var origin: String

    private val json = Json { ignoreUnknownKeys = true }
    private val callLog = mutableListOf<String>()

    private fun legendJson(raceId: Int = 8, checkpointIds: List<Int> = listOf(101)) = buildString {
        append("""{"race":$raceId,"checkpoints":[""")
        checkpointIds.forEachIndexed { index, id ->
            if (index > 0) append(",")
            append("""{"id":$id,"number":${index + 1},"cost":10,"type":"kp","description":"КП $id"}""")
        }
        append("]}")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        origin = server.url("/").toString()
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret-123",
            installIdProvider = { "install-abc" },
            appVersion = "2.0.1",
            nowSeconds = { 1718200000L },
        )
        val apiClient = ApiClient(origin, OkHttpClient.Builder().addInterceptor(interceptor).build(), json)
        checkpointDao = FakeCheckpointDao(callLog)
        tagDao = FakeTagDao(callLog)
        syncMetaDao = FakeLegendSyncMetaDao(callLog)
        repository = LegendRepository(apiClient, checkpointDao, tagDao, syncMetaDao, origin, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_mapsEntitiesAndStoresEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody(legendJson(checkpointIds = listOf(101, 102))),
        )

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        val checkpoints = repository.checkpointsForRace(8).first()
        assertEquals(2, checkpoints.size)
        val cp = checkpoints[0]
        assertEquals(101, cp.id)
        assertEquals(8, cp.raceId)
        assertEquals(1, cp.number)
        assertEquals(10, cp.cost)
        assertEquals("kp", cp.type)
        assertEquals("", cp.color)

        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/legend"))
    }

    @Test
    fun success_mapsColorToEntity() = runTest {
        val payload = """{"race":8,"checkpoints":[
            {"id":101,"number":1,"cost":10,"type":"kp","description":"A","color":"blue"},
            {"id":102,"number":2,"cost":5,"type":"kp","description":"B"}
        ]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        repository.refreshLegend(8)

        val checkpoints = repository.checkpointsForRace(8).first()
        assertEquals("blue", checkpoints.find { it.id == 101 }?.color)
        assertEquals("", checkpoints.find { it.id == 102 }?.color)
    }

    @Test
    fun success_writesDataBeforeEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(legendJson()),
        )

        repository.refreshLegend(8)

        assertEquals(listOf("replaceAllForRace", "replaceAllTags", "upsertEtag"), callLog)
    }

    @Test
    fun success_withoutEtag_storesCheckpointsButSkipsEtagSave() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(legendJson()))

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        assertEquals(1, repository.checkpointsForRace(8).first().size)
        assertNull(syncMetaDao.getEtag(origin, "race/8/legend"))
        assertEquals(listOf("replaceAllForRace", "replaceAllTags"), callLog)
    }

    @Test
    fun notModified_leavesDataUntouched() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 99, raceId = 8)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/legend", "\"v1\""))
        callLog.clear()
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(RefreshResult.NotModified, repository.refreshLegend(8))

        val checkpoints = repository.checkpointsForRace(8).first()
        assertEquals(1, checkpoints.size)
        assertTrue("DAO must not be written on 304", callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun offline_returnsOfflineAndLeavesDataUntouched() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 99, raceId = 8)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/legend", "\"existing\""))
        callLog.clear()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RefreshResult.Offline, repository.refreshLegend(8))

        assertEquals(1, repository.checkpointsForRace(8).first().size)
        assertEquals("\"existing\"", syncMetaDao.getEtag(origin, "race/8/legend"))
        assertTrue(callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(RefreshResult.Forbidden, repository.refreshLegend(8))
    }

    @Test
    fun serverError_returnsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RefreshResult.HttpError(500), repository.refreshLegend(8))
    }

    @Test
    fun differentRaceIds_useDifferentSyncResources() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"").setBody(legendJson(raceId = 8)),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"b\"")
                .setBody(legendJson(raceId = 9, checkpointIds = listOf(201))),
        )

        repository.refreshLegend(8)
        repository.refreshLegend(9)

        assertEquals("\"a\"", syncMetaDao.getEtag(origin, "race/8/legend"))
        assertEquals("\"b\"", syncMetaDao.getEtag(origin, "race/9/legend"))

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.path!!.contains("/app/race/8/legend/"))
        assertTrue(second.path!!.contains("/app/race/9/legend/"))
    }

    @Test
    fun emptyCheckpoints_replacesExistingRows() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 55, raceId = 8)))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"race":8,"checkpoints":[]}"""),
        )

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        assertTrue(repository.checkpointsForRace(8).first().isEmpty())
    }

    @Test
    fun success_mapsLockedCheckpointAndTags() = runTest {
        val body = """
            {"race":8,"checkpoints":[
              {"id":101,"number":1,"cost":10,"type":"kp","description":"open"},
              {"id":102,"number":2,"type":"kp","enc":{"iv":"AAAA","ct":"BBBB"}}
            ],"tags":[
              {"bid":"abc123","point":101,"check_method":"nfc"},
              {"bid":"def456","point":102,"check_method":"nfc","iv":"IV","ct":"CT"}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        val checkpoints = repository.checkpointsForRace(8).first()
        val locked = checkpoints.single { it.id == 102 }
        assertTrue(locked.locked)
        assertNull(locked.cost)
        assertNull(locked.description)
        assertEquals("AAAA", locked.encIv)
        assertEquals("BBBB", locked.encCt)
        val open = checkpoints.single { it.id == 101 }
        assertFalse(open.locked)
        assertEquals(10, open.cost)

        val tags = tagDao.observeTagsForRace(8).first()
        assertEquals(2, tags.size)
        assertNull(tags.single { it.bid == "abc123" }.iv)
        assertEquals("IV", tags.single { it.bid == "def456" }.iv)
    }

    @Test
    fun unlock_revealsAndPersistsCheckpointPlaintext() = runTest {
        val code = ByteArray(16) { (it * 5).toByte() }
        val cpId = 102
        val contentKey = ByteArray(32) { (it + 2).toByte() }
        val enc = seal(contentKey, """{"cost":7,"description":"Грот"}""".toByteArray(), cpId.toString().toByteArray(Charsets.US_ASCII))
        val bundleJson = """{"$cpId":"${contentKey.toByteString().base64()}"}"""
        val wrapKey = LegendCrypto.deriveWrapKey(code)
        val bundle = seal(wrapKey, bundleJson.toByteArray(), LegendCrypto.bid(code).toByteArray(Charsets.US_ASCII))

        checkpointDao.setCheckpoints(
            listOf(lockedCheckpoint(id = cpId, raceId = 8, encIv = enc.iv, encCt = enc.ct)),
        )
        tagDao.setTags(
            listOf(
                TagEntity(
                    bid = LegendCrypto.bid(code),
                    raceId = 8,
                    point = cpId,
                    checkMethod = "nfc",
                    iv = bundle.iv,
                    ct = bundle.ct,
                ),
            ),
        )

        val outcome = repository.unlock(8, code)

        assertEquals(UnlockOutcome.Revealed(cpId, listOf(cpId)), outcome)
        val cp = repository.checkpointsForRace(8).first().single()
        assertEquals(7, cp.cost)
        assertEquals("Грот", cp.description)
        assertFalse("reveal must clear locked", cp.locked)
    }

    @Test
    fun unlock_unknownBidReturnsUnknown() = runTest {
        val outcome = repository.unlock(8, ByteArray(16) { 1 })
        assertEquals(UnlockOutcome.Unknown, outcome)
    }

    @Test
    fun unlock_openCpTagReturnsIdentityOnly() = runTest {
        val code = ByteArray(16) { 2 }
        tagDao.setTags(
            listOf(
                TagEntity(
                    bid = LegendCrypto.bid(code),
                    raceId = 8,
                    point = 101,
                    checkMethod = "nfc",
                    iv = null,
                    ct = null,
                ),
            ),
        )

        assertEquals(UnlockOutcome.IdentityOnly(101), repository.unlock(8, code))
    }

    @Test
    fun unlock_partialEnvelopeReturnsFailed() = runTest {
        val code = ByteArray(16) { 4 }
        tagDao.setTags(
            listOf(
                TagEntity(
                    bid = LegendCrypto.bid(code),
                    raceId = 8,
                    point = 101,
                    checkMethod = "nfc",
                    iv = "someIv",
                    ct = null,
                ),
            ),
        )
        assertTrue(repository.unlock(8, code) is UnlockOutcome.Failed)
    }

    @Test
    fun unlock_tamperedCiphertextReturnsFailed() = runTest {
        val code = ByteArray(16) { 3 }
        val wrapKey = LegendCrypto.deriveWrapKey(code)
        val bundle = seal(wrapKey, """{"102":"AAAA"}""".toByteArray(), LegendCrypto.bid(code).toByteArray(Charsets.US_ASCII))
        val tamperedCt = (if (bundle.ct[0] == 'A') 'B' else 'A') + bundle.ct.substring(1)
        tagDao.setTags(
            listOf(
                TagEntity(
                    bid = LegendCrypto.bid(code),
                    raceId = 8,
                    point = 102,
                    checkMethod = "nfc",
                    iv = bundle.iv,
                    ct = tamperedCt,
                ),
            ),
        )

        assertTrue(repository.unlock(8, code) is UnlockOutcome.Failed)
    }

    /** Test-only AES-256-GCM seal, mirroring [LegendCrypto.open]'s decrypt direction. */
    private fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): EncBlob {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return EncBlob(iv = iv.toByteString().base64(), ct = ct.toByteString().base64())
    }

    private fun checkpointEntity(id: Int, raceId: Int) = CheckpointEntity(
        id = id,
        raceId = raceId,
        number = 1,
        cost = 10,
        type = "kp",
        description = "test",
    )

    private fun lockedCheckpoint(id: Int, raceId: Int, encIv: String, encCt: String) = CheckpointEntity(
        id = id,
        raceId = raceId,
        number = 1,
        cost = null,
        type = "kp",
        description = null,
        locked = true,
        encIv = encIv,
        encCt = encCt,
    )
}

private class FakeCheckpointDao(private val callLog: MutableList<String>) : CheckpointDao {
    private val checkpoints = MutableStateFlow<List<CheckpointEntity>>(emptyList())

    fun setCheckpoints(value: List<CheckpointEntity>) {
        checkpoints.value = value
    }

    override fun observeCheckpointsForRace(raceId: Int): Flow<List<CheckpointEntity>> =
        checkpoints.map { list -> list.filter { it.raceId == raceId } }

    override suspend fun insertCheckpoints(checkpoints: List<CheckpointEntity>) {
        this.checkpoints.value = this.checkpoints.value + checkpoints
    }

    override suspend fun deleteCheckpointsForRace(raceId: Int) {
        checkpoints.value = checkpoints.value.filterNot { it.raceId == raceId }
    }

    override suspend fun revealedForRace(raceId: Int): List<CheckpointEntity> =
        checkpoints.value.filter { it.raceId == raceId && it.cost != null }

    override suspend fun reveal(id: Int, cost: Int, description: String?) {
        checkpoints.value = checkpoints.value.map {
            if (it.id == id) it.copy(cost = cost, description = description, locked = false) else it
        }
    }

    override suspend fun getCheckpointsForRace(raceId: Int): List<CheckpointEntity> =
        checkpoints.value.filter { it.raceId == raceId }

    override suspend fun replaceAllForRace(raceId: Int, checkpoints: List<CheckpointEntity>) {
        deleteCheckpointsForRace(raceId)
        insertCheckpoints(checkpoints)
        callLog.add("replaceAllForRace")
    }
}

private class FakeTagDao(private val callLog: MutableList<String>) : TagDao {
    private val tags = MutableStateFlow<List<TagEntity>>(emptyList())

    fun setTags(value: List<TagEntity>) {
        tags.value = value
    }

    override fun observeTagsForRace(raceId: Int): Flow<List<TagEntity>> =
        tags.map { list -> list.filter { it.raceId == raceId } }

    override suspend fun getByBid(bid: String, raceId: Int): TagEntity? =
        tags.value.firstOrNull { it.bid == bid && it.raceId == raceId }

    override suspend fun insertTags(tags: List<TagEntity>) {
        this.tags.value = this.tags.value + tags
    }

    override suspend fun deleteTagsForRace(raceId: Int) {
        tags.value = tags.value.filterNot { it.raceId == raceId }
    }

    override suspend fun replaceAllForRace(raceId: Int, tags: List<TagEntity>) {
        deleteTagsForRace(raceId)
        insertTags(tags)
        callLog.add("replaceAllTags")
    }
}

private class FakeLegendSyncMetaDao(private val callLog: MutableList<String>) : SyncMetaDao {
    private val store = mutableMapOf<Pair<String, String>, String>()

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
    }
}
