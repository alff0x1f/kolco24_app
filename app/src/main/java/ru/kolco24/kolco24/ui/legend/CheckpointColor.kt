package ru.kolco24.kolco24.ui.legend

/**
 * Pure, Android-free mapping of the server's named checkpoint color token to an app-side enum.
 * No Compose imports — this is JVM-unit-tested (mirrors `ScanSession.kt` / `TeamPickerLogic.kt`).
 * The token → pixel `Color` mapping lives in `LegendScreen.kt` (a Compose file), never here.
 *
 * The server sends `color` as a named semantic token (`""`, `"red"`, `"blue"`, `"green"`,
 * `"yellow"`, `"orange"`, `"purple"`), not hex/RGB. `""` and any unknown/future token parse to
 * `null` (forward-compatible — never crashes on a token added server-side later).
 */
enum class CheckpointColor { RED, BLUE, GREEN, YELLOW, ORANGE, PURPLE }

/** Parse a server color token to [CheckpointColor]; `null` for `""` or any unrecognized token. */
fun parseCheckpointColor(token: String): CheckpointColor? =
    when (token.trim().lowercase()) {
        "red" -> CheckpointColor.RED
        "blue" -> CheckpointColor.BLUE
        "green" -> CheckpointColor.GREEN
        "yellow" -> CheckpointColor.YELLOW
        "orange" -> CheckpointColor.ORANGE
        "purple" -> CheckpointColor.PURPLE
        else -> null
    }
