package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import ru.kolco24.kolco24.data.Converters

@Entity(tableName = "nfc_check")
class NfcCheck(
    var pointNfc: String,
    var pointNumber: Int,
    var memberNfcId: String,
    @TypeConverters(Converters::class)
    var time: Long
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
    var isSyncLocal = false
    var isSync = false
}
