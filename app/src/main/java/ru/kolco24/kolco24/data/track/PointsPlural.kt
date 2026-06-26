package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.pluralRu

/** Correctly-declined «точка» for a GPS-fix count. */
fun pointsWord(count: Int): String = pluralRu(count, "точка", "точки", "точек")

/** «N точка/точки/точек». */
fun pointsLabel(count: Int): String = "$count ${pointsWord(count)}"

/** Correctly-declined «сегмент» for a recording-session count. */
fun segmentsWord(count: Int): String = pluralRu(count, "сегмент", "сегмента", "сегментов")

/** «N сегмент/сегмента/сегментов». */
fun segmentsLabel(count: Int): String = "$count ${segmentsWord(count)}"
