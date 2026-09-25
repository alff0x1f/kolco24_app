package ru.kolco24.kolco24.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A race category (distance/group). Belongs to a race via [raceId]. Mirrors `CategoryDto`.
 * The server field `order` is a reserved SQL word, so the column is named `sortOrder`.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Int,
    val raceId: Int,
    val code: String,
    val shortName: String,
    val name: String,
    val sortOrder: Int,
    /**
     * Control time (КВ) in minutes; `0` = not set (server sends `0` or omits the key). Added in v7
     * ([AppDatabase.MIGRATION_6_7]); the default lives both here and in the migration DDL so a fresh
     * install and an upgrade produce the same schema.
     */
    @ColumnInfo(defaultValue = "0") val controlTime: Int = 0,
)
