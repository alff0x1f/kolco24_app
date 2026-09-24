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

/**
 * Pin icons for the map's `SymbolLayer`: a red circle with a white outline and the КП number in
 * white `RobotoMono` bold. Drawn as plain bitmaps (registered via `style.addImage` under
 * [pinIconName]) so no glyphs are needed — MapLibre glyphs would load from the network.
 *
 * Cached per number for the process lifetime; a race has at most a few dozen КП, so the cache
 * stays tiny. Main-thread only (the map view calls it from composition / style callbacks).
 */
object PinBitmaps {

    private const val DIAMETER_DP = 30f
    private const val OUTLINE_DP = 2f

    private val cache = HashMap<Int, Bitmap>()
    private var cachedDensity = 0f
    private var typeface: Typeface? = null

    fun get(context: Context, number: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        if (density != cachedDensity) {
            cache.clear()
            cachedDensity = density
        }
        return cache.getOrPut(number) { draw(context, number, density) }
    }

    private fun draw(context: Context, number: Int, density: Float): Bitmap {
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
            typeface = monoTypeface(context)
            textAlign = Paint.Align.CENTER
            // Shrink 3-digit numbers so they stay inside the circle.
            textSize = size * if (label.length >= 3) 0.36f else 0.46f
        }
        val baseline = center - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, center, baseline, text)
        return bitmap
    }

    private fun monoTypeface(context: Context): Typeface =
        typeface ?: (
            runCatching { ResourcesCompat.getFont(context, R.font.roboto_mono_bold) }.getOrNull()
                ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            ).also { typeface = it }
}
