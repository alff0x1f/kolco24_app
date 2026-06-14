package ru.kolco24.kolco24.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Today as a `YYYY-MM-DD` string (no `java.time` — minSdk 24 without core library desugaring). */
fun todayIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
