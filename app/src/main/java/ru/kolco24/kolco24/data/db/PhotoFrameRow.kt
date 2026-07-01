package ru.kolco24.kolco24.data.db

/** One photo-carrying mark's raw frame-upload flags — folded into per-target frame counts by
 *  `foldPhotoFrameCounts` in [ru.kolco24.kolco24.data.MarkRepository]. */
data class PhotoFrameRow(
    val photoPath: String?,
    val photosUploadedLocal: Boolean,
    val photosUploadedCloud: Boolean,
)
