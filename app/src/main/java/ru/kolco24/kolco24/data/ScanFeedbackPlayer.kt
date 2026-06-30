package ru.kolco24.kolco24.data

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.ui.scan.ScanFeedbackKind

/**
 * Android adapter that plays the three scan outcomes (sound + optional vibration). Owned by
 * `AppContainer` and constructed eagerly during `Application.onCreate` so the asynchronous
 * `SoundPool.load()` of all three clips has time to finish before the first NFC tap is possible.
 *
 * Untested by convention (Android adapter, like the location engines / NfcA adapters).
 *
 * **Threading:** `SoundPool.play` and `Vibrator.vibrate` are thread-safe, so this is callable from
 * the NFC binder thread and from coroutines alike.
 *
 * **Readiness:** `SoundPool.load()` is asynchronous; `SoundPool.play()` on an unloaded id returns 0
 * (silent no-op), so a missed sound on a very early tap is acceptable. Vibration always fires
 * regardless — a missed *vibration* is not acceptable.
 */
class ScanFeedbackPlayer(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val successSoundId: Int
    private val failureSoundId: Int
    private val neutralSoundId: Int

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        successSoundId = soundPool.load(context, R.raw.beep_ok3, 1)
        failureSoundId = soundPool.load(context, R.raw.beep_err, 1)
        neutralSoundId = soundPool.load(context, R.raw.beep_scan, 1)
    }

    /** Recognized chip: crisp tick + a single short pulse. */
    fun success() {
        playSound(successSoundId)
        vibrate(longArrayOf(0, 40))
    }

    /** Recognized-but-rejected tap: error tone + a double buzz. */
    fun failure() {
        playSound(failureSoundId)
        vibrate(longArrayOf(0, 60, 80, 60))
    }

    /** Soft "tap registered" tick, no vibration. */
    fun neutral() {
        playSound(neutralSoundId)
    }

    /** Dispatch to [success]/[failure]/[neutral] by [kind]. */
    fun play(kind: ScanFeedbackKind) = when (kind) {
        ScanFeedbackKind.Success -> success()
        ScanFeedbackKind.Failure -> failure()
        ScanFeedbackKind.Neutral -> neutral()
    }

    private fun playSound(soundId: Int) {
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
