package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_points")
class Photo
    (
    var teamId: Int,
    var pointNumber: Int,
    var photoUrl: String,
    var photoThumbUrl: String,
    var photoTime: String,
    var time: Long,
    var pointNfc: String,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0

    /* possible values are "new", "send_info", "send_photo", "send_photo_info" */
    var status = "new"
    var isSyncLocal = false
    var isSync = false

    companion object {
        const val NEW = "new"
    }
}
