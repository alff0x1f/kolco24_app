package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.db.MemberChipBindingDao
import ru.kolco24.kolco24.data.db.MemberChipBindingEntity

class MemberChipBindingRepositoryTest {

    private lateinit var dao: FakeMemberChipBindingDao
    private lateinit var repository: MemberChipBindingRepository

    @Before
    fun setUp() {
        dao = FakeMemberChipBindingDao()
        repository = MemberChipBindingRepository(dao)
    }

    @Test
    fun bind_thenObserve_returnsBinding() = runTest {
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "AABB", participantNumber = 101)

        val bindings = repository.observeForTeam(7).first()
        assertEquals(1, bindings.size)
        val binding = bindings.single()
        assertEquals(1, binding.numberInTeam)
        assertEquals("AABB", binding.nfcUid)
        assertEquals(101, binding.participantNumber)
    }

    @Test
    fun rebindSameSlot_overwrites() = runTest {
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "AABB", participantNumber = 101)
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "CCDD", participantNumber = 102)

        val bindings = repository.observeForTeam(7).first()
        assertEquals(1, bindings.size)
        assertEquals("CCDD", bindings.single().nfcUid)
        assertEquals(102, bindings.single().participantNumber)
    }

    @Test
    fun unbind_removesSlot() = runTest {
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "AABB", participantNumber = 101)
        repository.bind(teamId = 7, numberInTeam = 2, nfcUid = "CCDD", participantNumber = 102)

        repository.unbind(teamId = 7, numberInTeam = 1)

        val bindings = repository.observeForTeam(7).first()
        assertEquals(listOf(2), bindings.map { it.numberInTeam })
    }

    @Test
    fun findByUid_returnsOwningSlot() = runTest {
        repository.bind(teamId = 7, numberInTeam = 3, nfcUid = "AABB", participantNumber = 101)

        val owner = repository.findByUid("AABB")
        assertEquals(7, owner?.teamId)
        assertEquals(3, owner?.numberInTeam)
        assertNull(repository.findByUid("DEADBEEF"))
    }

    @Test
    fun bind_reassignsChip_movesAtomicallyBetweenSlots() = runTest {
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "AABB", participantNumber = 101)

        // Same chip now bound to a different slot: the old slot must be gone, the new one present.
        repository.bind(teamId = 7, numberInTeam = 2, nfcUid = "AABB", participantNumber = 101)

        val bindings = repository.observeForTeam(7).first()
        assertEquals(1, bindings.size)
        assertEquals(2, bindings.single().numberInTeam)
        assertEquals("AABB", repository.findByUid("AABB")?.nfcUid)
    }

    @Test
    fun observeForTeam_doesNotReturnBindingsOfOtherTeams() = runTest {
        repository.bind(teamId = 7, numberInTeam = 1, nfcUid = "AABB", participantNumber = 101)
        repository.bind(teamId = 8, numberInTeam = 1, nfcUid = "CCDD", participantNumber = 202)

        val team7 = repository.observeForTeam(7).first()
        val team8 = repository.observeForTeam(8).first()

        assertEquals(1, team7.size)
        assertEquals("AABB", team7.single().nfcUid)
        assertEquals(1, team8.size)
        assertEquals("CCDD", team8.single().nfcUid)
    }
}

private class FakeMemberChipBindingDao : MemberChipBindingDao {
    private val rows = MutableStateFlow<List<MemberChipBindingEntity>>(emptyList())

    override fun observeForTeam(teamId: Int): Flow<List<MemberChipBindingEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId }.sortedBy { it.numberInTeam } }

    override suspend fun findByUid(nfcUid: String): MemberChipBindingEntity? =
        rows.value.firstOrNull { it.nfcUid == nfcUid }

    override suspend fun upsert(binding: MemberChipBindingEntity) {
        rows.value = rows.value.filterNot {
            it.teamId == binding.teamId && it.numberInTeam == binding.numberInTeam
        } + binding
    }

    override suspend fun deleteSlot(teamId: Int, numberInTeam: Int) {
        rows.value = rows.value.filterNot { it.teamId == teamId && it.numberInTeam == numberInTeam }
    }

    override suspend fun deleteByUid(nfcUid: String) {
        rows.value = rows.value.filterNot { it.nfcUid == nfcUid }
    }
}
