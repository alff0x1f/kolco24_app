import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import ru.kolco24.kolco24.data.entities.NfcCheck
import ru.kolco24.kolco24.data.NfcCheckRepository

class NfcCheckViewModel(application: Application?) : AndroidViewModel(application!!) {
    private val mRepository: NfcCheckRepository
    val allNfcChecks: LiveData<List<NfcCheck>>

    init {
        mRepository = NfcCheckRepository(application)
        allNfcChecks = mRepository.allNfcChecks
    }

    fun getNfcCheckById(id: Int): NfcCheck {
        return mRepository.getNfcCheckById(id)
    }

    fun getNotSyncNfcCheck(): List<NfcCheck> {
        return mRepository.notSyncNfcCheck
    }

    fun insert(nfcCheck: NfcCheck?) {
        mRepository.insert(nfcCheck)
    }

    fun deleteAll() {
        mRepository.deleteAll()
    }
}
