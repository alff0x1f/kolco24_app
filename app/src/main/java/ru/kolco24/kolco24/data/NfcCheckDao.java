package ru.kolco24.kolco24.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import ru.kolco24.kolco24.data.entities.NfcCheck;

@Dao
public interface NfcCheckDao {
    @Insert
    void insert(NfcCheck nfcCheck);

    @Query("SELECT * FROM nfc_check")
    LiveData<List<NfcCheck>> getAllNfcChecks();

    @Query("SELECT * FROM nfc_check WHERE id = :id")
    NfcCheck getNfcCheckById(int id);

    @Query("DELETE FROM nfc_check WHERE id = :id")
    void deleteNfcCheckById(int id);

    @Query("DELETE FROM nfc_check")
    void deleteAllNfcChecks();

    @Query("SELECT * FROM nfc_check")
    List<NfcCheck> getNotSyncNfcCheck();

    @Query("SELECT count(DISTINCT pointNumber) FROM nfc_check;")
    LiveData<Integer> getNfcCheckCount();
}
