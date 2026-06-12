package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A race category (distance/group). Belongs to a race via [raceId]. Mirrors `CategoryDto`.
 * The server field `order` is a reserved SQL word, so the column is named `sort_order`.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Int,
    val raceId: Int,
    val code: String,
    val shortName: String,
    val name: String,
    val sortOrder: Int,
)
