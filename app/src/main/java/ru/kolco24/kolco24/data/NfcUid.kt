package ru.kolco24.kolco24.data

/**
 * Normalize a raw NFC tag id into the server's pool format: each byte as a zero-padded,
 * uppercase two-char hex pair (e.g. `0x04` → `"04"`), concatenated. The `member_tags` pool
 * arrives already normalized (trim + UPPERCASE), so read UIDs are normalized the same way
 * before comparing. Empty array → empty string.
 */
fun normalizeNfcUid(raw: ByteArray): String {
    val sb = StringBuilder(raw.size * 2)
    for (b in raw) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()
