package ru.kolco24.kolco24.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.ui.theme.CpColorRed

private const val DIAMETER_DP = 30f
private const val OUTLINE_DP = 2f

/**
 * Pin icon for the map's `SymbolLayer`: a red circle with a white outline and the КП number in
 * white `RobotoMono` bold. Drawn as a plain bitmap (registered via `style.addImage` under
 * [pinIconName]) so no glyphs are needed — MapLibre glyphs would load from the network.
 *
 * No cache: the map view only draws numbers the current style lacks, and a race has at most a few
 * dozen КП. Main-thread only (called from composition effects / style callbacks).
 */
fun pinBitmap(context: Context, number: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (DIAMETER_DP * density).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val outline = OUTLINE_DP * density

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawCircle(center, center, center, fill)
    fill.color = CpColorRed.toArgb()
    canvas.drawCircle(center, center, center - outline, fill)

    val label = number.toString()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.roboto_mono_bold)
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        // Shrink 3-digit numbers so they stay inside the circle.
        textSize = size * if (label.length >= 3) 0.36f else 0.46f
    }
    val baseline = center - (text.descent() + text.ascent()) / 2f
    canvas.drawText(label, center, baseline, text)
    return bitmap
}
