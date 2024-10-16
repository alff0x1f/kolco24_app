package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "point_tags",
    foreignKeys = [ForeignKey(
        entity = Checkpoint::class,
        parentColumns = ["id"],
        childColumns = ["checkpointId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CheckpointTag(
    @PrimaryKey(autoGenerate = true) val id: Int,
    var checkpointId: Int,
    var tagUID: String,
    var checkMethod: String
)