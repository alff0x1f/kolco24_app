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

private const val STOP_TEXT_DP = 11f
private const val STOP_PAD_H_DP = 5f
private const val STOP_PAD_V_DP = 2f

/**
 * Stop label icon («12 мин»): a translucent dark capsule with a 1 dp white outline and white
 * `RobotoMono` bold text — a bitmap for the same no-glyphs reason as [pinBitmap]. Registered under
 * [stopIconName]. Main-thread only.
 */
fun stopBitmap(context: Context, label: String): Bitmap {
    val density = context.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.roboto_mono_bold)
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = STOP_TEXT_DP * density
    }
    val width = (text.measureText(label) + 2 * STOP_PAD_H_DP * density).toInt().coerceAtLeast(1)
    val height = (text.descent() - text.ascent() + 2 * STOP_PAD_V_DP * density).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val half = density / 2f
    val radius = height / 2f - half

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(0xD9, 0x1A, 0x1A, 0x1A) }
    canvas.drawRoundRect(half, half, width - half, height - half, radius, radius, fill)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    canvas.drawRoundRect(half, half, width - half, height - half, radius, radius, stroke)

    val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
    canvas.drawText(label, width / 2f, baseline, text)
    return bitmap
}
