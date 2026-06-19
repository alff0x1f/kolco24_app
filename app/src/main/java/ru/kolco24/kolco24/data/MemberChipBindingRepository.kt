package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.db.MemberChipBindingDao
import ru.kolco24.kolco24.data.db.MemberChipBindingEntity

/**
 * Single source of truth for the **local-only** NFC chip bindings — which physical bracelet is bound
 * to which member slot `(teamId, numberInTeam)` of a selected team. Wraps [MemberChipBindingDao]; the
 * bind UI reads [observeForTeam] and the duplicate check uses [findByUid]. This data is never uploaded
 * to the backend.
 *
 * The warn+allow *decision* (chip already bound to another slot?) stays in the bind sheet; this repo
 * only performs writes. [bind] always goes through the atomic [MemberChipBindingDao.reassign] so a
 * chip is never momentarily on two slots — whether it is a fresh bind or a reassignment, any prior
 * slot holding the same uid is dropped in the same transaction as the new write.
 */
class MemberChipBindingRepository(
    private val bindingDao: MemberChipBindingDao,
) {
    /** Live bindings for one team, ordered by member slot number. */
    fun observeForTeam(teamId: Int): Flow<List<MemberChipBindingEntity>> =
        bindingDao.observeForTeam(teamId)

    /** The slot currently holding [nfcUid], or `null` when this chip is bound nowhere. */
    suspend fun findByUid(nfcUid: String): MemberChipBindingEntity? = bindingDao.findByUid(nfcUid)

    /**
     * Binds [nfcUid] to the `(teamId, numberInTeam)` slot. Atomically drops any other slot already
     * holding this chip (reassignment) before writing, so the chip lives on exactly one slot.
     */
    suspend fun bind(teamId: Int, numberInTeam: Int, nfcUid: String, participantNumber: Int) {
        bindingDao.reassign(
            MemberChipBindingEntity(
                teamId = teamId,
                numberInTeam = numberInTeam,
                nfcUid = nfcUid,
                participantNumber = participantNumber,
            ),
        )
    }

    /** Removes the binding from the `(teamId, numberInTeam)` slot (no-op when the slot is empty). */
    suspend fun unbind(teamId: Int, numberInTeam: Int) = bindingDao.deleteSlot(teamId, numberInTeam)
}
