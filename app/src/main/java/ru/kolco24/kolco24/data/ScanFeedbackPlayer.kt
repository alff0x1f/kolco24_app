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
    private val shutterSoundId: Int

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        successSoundId = soundPool.load(context, R.raw.beep_ok3, 1)
        failureSoundId = soundPool.load(context, R.raw.beep_err, 1)
        shutterSoundId = soundPool.load(context, R.raw.shutter, 1)
    }

    /** Recognized chip: crisp tick + a single short pulse. */
    fun success() {
        playSound(successSoundId)
        vibrate(SUCCESS_VIBRATION_PATTERN)
    }

    /** Recognized-but-rejected tap: error tone + a double buzz. */
    fun failure() {
        playSound(failureSoundId)
        vibrate(longArrayOf(0, 60, 80, 60))
    }

    /** Unknown / not-a-working-chip tap: a single short buzz, no sound. */
    fun neutral() {
        vibrate(longArrayOf(0, 50))
    }

    /** Camera shutter click, played immediately on capture press — no vibration (photo-mark only). */
    fun shutter() {
        playSound(shutterSoundId)
    }

    /** Tactile confirmation that a photo-mark frame was written to disk — vibration only, no sound
     *  (the audible feedback already happened at [shutter], before the write finished). */
    fun photoCaptureConfirm() {
        vibrate(SUCCESS_VIBRATION_PATTERN)
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

    private companion object {
        val SUCCESS_VIBRATION_PATTERN = longArrayOf(0, 40)
    }
}
