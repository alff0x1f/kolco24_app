package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_check")
class NfcCheck(
    var pointNfc: String,
    var pointNumber: Int,
    var memberNfcId: String,
    var createDt: String
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}
