package ru.kolco24.kolco24.ui.upload

import ru.kolco24.kolco24.data.track.TargetUploadOutcome

/**
 * One upload target's progress for the status row: how many of [total] points are uploaded to this
 * target and its last flush [outcome] (`null` until a flush has reported, or after a destructive
 * clear). UI-composition model, declared in a neutral package shared by the track/marks/photo status
 * consumers (never coupled back to any one of them).
 */
data class TargetLine(val uploaded: Int, val outcome: TargetUploadOutcome?)

/**
 * The adaptive upload-status view-model the host derives by joining the durable per-target counts
 * with the transient in-memory outcomes for the selected scope. [fullyUploaded] is the calm-state
 * gate: everything counted and both targets caught up.
 */
data class TrackUploadStatus(val total: Int, val local: TargetLine, val cloud: TargetLine) {
    val fullyUploaded: Boolean get() = total > 0 && local.uploaded == total && cloud.uploaded == total
}

enum class UploadSummaryState { Loading, Empty, Pending, AllSent }

data class UploadSummary(val label: String, val state: UploadSummaryState)

/**
 * The one-line «Загрузка данных» row subtitle on the Команда tab. Only the cloud target counts as
 * «sent» (iOS parity). Track shows as a bare «трек» — its point count would dwarf the marks and read
 * as a scary number. While ![ready] a null status is unknown rather than empty, so no verdict is given.
 */
fun uploadSummary(
    marks: TrackUploadStatus?,
    photos: TrackUploadStatus?,
    track: TrackUploadStatus?,
    judge: TrackUploadStatus?,
    ready: Boolean = true,
): UploadSummary {
    if (!ready) return UploadSummary("Проверяем…", UploadSummaryState.Loading)
    val all = listOf(marks, photos, track, judge)
    if (all.all { (it?.total ?: 0) <= 0 }) return UploadSummary("Пока нечего загружать", UploadSummaryState.Empty)
    fun pending(s: TrackUploadStatus?) = if (s == null) 0 else maxOf(0, s.total - s.cloud.uploaded)
    val parts = listOfNotNull(
        pending(marks).takeIf { it > 0 }?.let { "$it ${marksWord(it)}" },
        pending(photos).takeIf { it > 0 }?.let { "$it фото" },
        "трек".takeIf { pending(track) > 0 },
        pending(judge).takeIf { it > 0 }?.let { "$it суд. ${marksWord(it)}" },
    )
    if (parts.isEmpty()) return UploadSummary("Всё отправлено", UploadSummaryState.AllSent)
    return UploadSummary("Не отправлено: " + parts.joinToString(", "), UploadSummaryState.Pending)
}

private fun marksWord(n: Int): String {
    val rem100 = n % 100
    val rem10 = n % 10
    return when {
        rem100 in 11..19 -> "отметок"
        rem10 == 1 -> "отметка"
        rem10 in 2..4 -> "отметки"
        else -> "отметок"
    }
}
