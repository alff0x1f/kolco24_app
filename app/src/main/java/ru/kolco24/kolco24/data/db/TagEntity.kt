package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One NFC tag of a race's legend. Maps a tag's [bid] (the derived `sha256(code)[:16]` identifier)
 * to the checkpoint ([point]) it belongs to. This entity doubles as the app's model.
 *
 * A tag that opens a **locked** checkpoint carries an unlock envelope ([iv]/[ct], Base64); a tag for
 * an open checkpoint has both null. [checkMethod] is the server's `check_method` string.
 *
 * The composite primary key `(raceId, bid)` keeps each race's tags isolated: physical NFC tags
 * reused across races cannot overwrite each other on a `REPLACE` insert. [raceId] and [point] are
 * indexed for per-race / per-CP lookups; the tag is matched on scan by its [bid] within [raceId].
 */
@Entity(
    tableName = "tags",
    primaryKeys = ["raceId", "bid"],
    indices = [Index("raceId"), Index("point")],
)
data class TagEntity(
    val raceId: Int,
    val bid: String,
    val point: Int,
    val checkMethod: String,
    val iv: String?,
    val ct: String?,
)
