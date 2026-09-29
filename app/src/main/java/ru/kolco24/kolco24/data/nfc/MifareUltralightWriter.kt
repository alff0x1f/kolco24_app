package ru.kolco24.kolco24.data.nfc

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException

/** Result of [writeChipCode]. Never thrown — surfaced as a value. */
sealed interface ChipWriteResult {
    /** All pages written and ACKed. */
    data object Success : ChipWriteResult

    /** Tag exposes no NfcA tech (not an ISO 14443-3A tag) — nothing written. */
    data object Unsupported : ChipWriteResult

    /** I/O error or NAK mid-write (tag moved away, write-protected, wrong chip, etc.). */
    data class Failed(val message: String) : ChipWriteResult

    /**
     * The chip already carries a `K24` record of **another** type (a КП chip under a bracelet write or
     * vice versa) — nothing written. [reason] is the user-facing RU message. Retrying is pointless.
     */
    data class WrongType(val reason: String) : ChipWriteResult
}

/** Bytes per MifareUltralight page. */
private const val PAGE_SIZE = 4

/** A code is one UUID = 16 bytes = 4 pages (4..7), present on every Ultralight variant. */
const val CHIP_CODE_BYTES = PAGE_SIZE * 4

// ---------------------------------------------------------------------------
// Raw on-chip format (header-first): page 4 = 'K' '2' '4' <packed>, pages 5–8 = 16-byte code.
// `packed = (version << 4) | type`; high nibble = version, low nibble = type. See docs/plans.
// Type/version constants are Int so the nibble math + the Int `type` param don't clash with Byte.
// ---------------------------------------------------------------------------

/** 24-bit magic 'K' '2' '4' (Kolco24 brand) — the "this is our chip" sentinel in page 4 bytes 0..2. */
private val MAGIC = byteArrayOf(0x4B, 0x32, 0x34)

/** Low-nibble chip type: КП (checkpoint). */
const val CHIP_TYPE_KP = 0x1

/** Low-nibble chip type: participant bracelet (server-issued secret code, written by admin). */
const val CHIP_TYPE_PARTICIPANT = 0x2

/** High-nibble format version. */
const val CHIP_FORMAT_VERSION = 0x1

/** Page holding the 4-byte header (magic + packed byte). */
const val HEADER_PAGE = 4

/** First page of the 16-byte code (pages 5..8). */
const val CODE_PAGE_START = 5

/** Header (4 bytes) + code (16 bytes) = 20 bytes = 5 pages (4..8). */
const val CHIP_RECORD_BYTES = PAGE_SIZE + CHIP_CODE_BYTES

/**
 * Build the raw chip record: 3-byte [MAGIC] + packed (`version<<4 | type`) byte + [code] (16 bytes),
 * 20 bytes total. Pure — no Android. `type` is an Int (nibble, 0..15); [code] must be 16 bytes.
 */
fun buildChipRecord(type: Int, code: ByteArray): ByteArray {
    require(code.size == CHIP_CODE_BYTES) { "code must be $CHIP_CODE_BYTES bytes" }
    require(type in 0..15) { "type must fit in a nibble (0..15)" }
    val packed = (((CHIP_FORMAT_VERSION shl 4) or type) and 0xFF).toByte()
    return MAGIC + packed + code
}

/**
 * Type nibble of a valid `K24` record in [pages] (magic + [CHIP_FORMAT_VERSION]), or null when
 * [pages] is too short, the magic mismatches, or the version is unknown. Pure.
 */
private fun chipRecordType(pages: ByteArray): Int? {
    if (pages.size < CHIP_RECORD_BYTES) return null
    for (i in MAGIC.indices) {
        if (pages[i] != MAGIC[i]) return null
    }
    val packed = pages[MAGIC.size].toInt() and 0xFF
    if ((packed ushr 4) and 0x0F != CHIP_FORMAT_VERSION) return null
    return packed and 0x0F
}

/**
 * Parse a raw chip record read from pages 4.. Returns the 16-byte code, or null when [pages] is too
 * short, the magic mismatches, the version nibble is not [CHIP_FORMAT_VERSION] (forward-incompat
 * guard), or the type nibble is not [type]. Trailing padding is tolerated. Pure — no Android.
 */
fun parseChipRecord(pages: ByteArray, type: Int): ByteArray? {
    if (chipRecordType(pages) != type) return null
    return pages.copyOfRange(PAGE_SIZE, PAGE_SIZE + CHIP_CODE_BYTES)
}

/** КП reader: [parseChipRecord] with [CHIP_TYPE_KP] — a participant record (type `0x2`) is rejected. */
fun parseChipRecord(pages: ByteArray): ByteArray? = parseChipRecord(pages, CHIP_TYPE_KP)

/**
 * Both record types decoded from one read of pages 4..8. The types share one nibble, so at most one
 * of [code] (КП) / [memberCode] (participant bracelet) is non-null; a blank or foreign chip has both
 * null.
 */
class ChipCodes(val code: ByteArray?, val memberCode: ByteArray?)

/** Decode raw [pages] (see [readRecordPages]) into [ChipCodes]. Pure. */
fun decodeChipPages(pages: ByteArray): ChipCodes =
    ChipCodes(
        code = parseChipRecord(pages, CHIP_TYPE_KP),
        memberCode = parseChipRecord(pages, CHIP_TYPE_PARTICIPANT),
    )

/** RU message for a chip that already carries a `K24` record of [type] (a wrong-type write target). */
fun wrongChipTypeMessage(type: Int): String = when (type) {
    CHIP_TYPE_KP -> "Это чип КП, а не браслет"
    CHIP_TYPE_PARTICIPANT -> "Это браслет участника"
    else -> "Чип другого типа"
}

/** Pre-write guard decision, see [writeGuardDecision]. */
sealed interface ChipWriteGuard {
    /** Blank/foreign chip, or a `K24` record of the same type (a rewrite). */
    data object Allow : ChipWriteGuard

    /** The current pages could not be read — don't write blind. */
    data object ReadFailed : ChipWriteGuard

    /** The chip carries a `K24` record of another type. */
    data class WrongType(val reason: String) : ChipWriteGuard
}

/**
 * Decide whether [record] may overwrite the chip whose current pages are [currentPages] (`null` = the
 * read failed). Anything with the `K24` magic that is not a same-type record of the supported version
 * is refused — including an unknown version. [writeRecord] zeroes the header first, so without this
 * check a bracelet write would silently destroy a КП chip (and vice versa). Pure.
 */
fun writeGuardDecision(currentPages: ByteArray?, record: ByteArray): ChipWriteGuard {
    require(record.size == CHIP_RECORD_BYTES) { "record must be $CHIP_RECORD_BYTES bytes" }
    if (currentPages == null) return ChipWriteGuard.ReadFailed
    if (currentPages.size < PAGE_SIZE || MAGIC.indices.any { currentPages[it] != MAGIC[it] }) {
        return ChipWriteGuard.Allow
    }
    // A K24 header of a version this build can't parse may still be a live chip of another type.
    val onChip = chipRecordType(currentPages) ?: return ChipWriteGuard.WrongType("Чип записан в другом формате")
    val pendingType = record[MAGIC.size].toInt() and 0x0F
    if (onChip != pendingType) return ChipWriteGuard.WrongType(wrongChipTypeMessage(onChip))
    return ChipWriteGuard.Allow
}

/** Uppercase hex of [code] (no separators) — for display/recording. */
fun chipCodeHex(code: ByteArray): String {
    val sb = StringBuilder(code.size * 2)
    for (b in code) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

/** Inverse of [chipCodeHex]; used to recover the bytes from the saveable hex state. */
fun chipCodeFromHex(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex must have even length" }
    return ByteArray(hex.length / 2) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** Ultralight / NTAG GET_VERSION opcode — returns an 8-byte product identifier. */
private const val CMD_GET_VERSION = 0x60.toByte()

/**
 * Map an 8-byte GET_VERSION response to a human-readable chip-model label. Product type lives at
 * byte 2 (`0x04` = NTAG, `0x03` = MIFARE Ultralight) and storage size at byte 6 (`0x0F` = NTAG213,
 * `0x11` = NTAG215, `0x13` = NTAG216). A short/empty response → "неизвестно". Pure — never throws.
 */
fun chipModelFromVersion(resp: ByteArray): String {
    if (resp.size < 8) return "неизвестно"
    val productType = resp[2].toInt() and 0xFF
    val storageSize = resp[6].toInt() and 0xFF
    return when (productType) {
        0x04 -> when (storageSize) {
            0x0F -> "NTAG213"
            0x11 -> "NTAG215"
            0x13 -> "NTAG216"
            else -> "NTAG (неизвестно)"
        }
        0x03 -> "MIFARE Ultralight"
        else -> "неизвестно"
    }
}

/**
 * Read the 8-byte GET_VERSION product identifier over NfcA raw [CMD_GET_VERSION]. Returns the bytes,
 * or null when the tag exposes no NfcA tech, NAKs the command, or errors. Blocking I/O — call off
 * the main thread; never throws. Pair with [chipModelFromVersion] for a label.
 */
fun readChipVersion(tag: Tag): ByteArray? {
    val nfcA = NfcA.get(tag) ?: return null
    return try {
        nfcA.connect()
        val resp = nfcA.transceive(byteArrayOf(CMD_GET_VERSION))
        if (resp.size < 8) null else resp
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        closeQuietly(nfcA)
    }
}

/**
 * Close [nfcA], swallowing errors. Android throws [SecurityException] («Tag … is out of date») from
 * connect/transceive/close on a [Tag] handle from an earlier discovery — a retained handle, so the
 * adapters treat it like an I/O failure.
 */
private fun closeQuietly(nfcA: NfcA) {
    try {
        nfcA.close()
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}

/** Ultralight / NTAG WRITE opcode — writes one 4-byte page: `[0xA2, page, b0, b1, b2, b3]`. */
private const val CMD_WRITE = 0xA2.toByte()

/** 4-bit ACK the tag returns for a successful WRITE (surfaced by NfcA as a single byte). */
private const val ACK = 0x0A.toByte()

/** NTAG21x / Ultralight EV1 FAST_READ — `[0x3A, start, end]` returns pages start..end in one frame. */
private const val CMD_FAST_READ = 0x3A.toByte()

/**
 * Minimal seam over an open NfcA connection: send one raw frame, get the response (or throw
 * [IOException]). Lets the command-sequencing logic in [writeRecord]/[readRecord] be JVM-tested
 * with a fake — the real adapter is `{ frame -> nfcA.transceive(frame) }`.
 */
fun interface NfcTransport {
    @Throws(IOException::class)
    fun transceive(frame: ByteArray): ByteArray
}

/** WRITE one page from `src[from until from+4]`; returns [ChipWriteResult.Failed] on NAK, else null. */
private fun writePage(t: NfcTransport, page: Int, src: ByteArray, from: Int): ChipWriteResult? {
    val frame = byteArrayOf(
        CMD_WRITE,
        page.toByte(),
        src[from], src[from + 1], src[from + 2], src[from + 3],
    )
    val response = t.transceive(frame)
    return if (response.isEmpty() || response[0] != ACK) {
        ChipWriteResult.Failed("Метка отклонила запись страницы $page")
    } else {
        null
    }
}

/**
 * Write the 20-byte [record] (header page 4 + code pages 5..8) over [t] in **header-last** order so
 * the header acts as a commit marker (an interrupted tap can never leave a valid header over a
 * half-written code):
 * 1. invalidate page 4 with an all-zero header (kills any prior magic before the code is overwritten),
 * 2. write the code (pages 5..8 = record bytes 4..19),
 * 3. write the valid header last (page 4 = record bytes 0..3).
 * Then read back over the **same** transport and return [ChipWriteResult.Failed] unless the parsed
 * code equals `record[4..19]`. Never throws.
 */
internal fun writeRecord(t: NfcTransport, record: ByteArray): ChipWriteResult {
    require(record.size == CHIP_RECORD_BYTES) { "record must be $CHIP_RECORD_BYTES bytes" }
    return try {
        // 1. invalidate page 4 (all-zero) before the code is touched.
        writePage(t, HEADER_PAGE, ByteArray(PAGE_SIZE), 0)?.let { return it }
        // 2. code pages 5..8 (record bytes 4..19).
        for (i in 0 until CHIP_CODE_BYTES / PAGE_SIZE) {
            val page = CODE_PAGE_START + i
            val from = PAGE_SIZE + i * PAGE_SIZE
            writePage(t, page, record, from)?.let { return it }
        }
        // 3. valid header last (commit marker).
        writePage(t, HEADER_PAGE, record, 0)?.let { return it }
        // Read back over the same open connection and verify the code round-trips as the same type.
        val expected = record.copyOfRange(PAGE_SIZE, CHIP_RECORD_BYTES)
        val type = record[MAGIC.size].toInt() and 0x0F
        val readBack = readRecordPages(t)?.let { parseChipRecord(it, type) }
        if (readBack == null || !readBack.contentEquals(expected)) {
            return ChipWriteResult.Failed("Чтение после записи не совпало")
        }
        ChipWriteResult.Success
    } catch (e: IOException) {
        ChipWriteResult.Failed(e.message ?: "Ошибка записи")
    }
}

/**
 * [writeRecord] behind [writeGuardDecision]: reads the current pages over the same connection first
 * and refuses a chip that carries a `K24` record of another type ([ChipWriteResult.WrongType]) or
 * whose pages can't be read ([ChipWriteResult.Failed] — retap). Never throws.
 */
internal fun writeRecordGuarded(t: NfcTransport, record: ByteArray): ChipWriteResult =
    when (val guard = writeGuardDecision(readRecordPages(t), record)) {
        ChipWriteGuard.Allow -> writeRecord(t, record)
        ChipWriteGuard.ReadFailed -> ChipWriteResult.Failed("Не удалось прочитать чип")
        is ChipWriteGuard.WrongType -> ChipWriteResult.WrongType(guard.reason)
    }

/**
 * Read the raw 20-byte record (pages 4..8) over [t], unparsed. Tries **FAST_READ** (`0x3A 04 08`) in
 * one transceive; treats an [IOException] **or** any response shorter than [CHIP_RECORD_BYTES] (a tag
 * may answer an unsupported command with a 1-byte NAK instead of throwing) as failure and falls back
 * to two plain **READ**s — page 4 (bytes 0..15) + page 8 (first 4 bytes = record bytes 16..19). Each
 * READ must return at least [READ_BLOCK] bytes; a short/NAK response or an [IOException] on either
 * READ → null. Never throws.
 */
internal fun readRecordPages(t: NfcTransport): ByteArray? {
    val fast = try {
        t.transceive(byteArrayOf(CMD_FAST_READ, HEADER_PAGE.toByte(), (HEADER_PAGE + 4).toByte()))
    } catch (_: IOException) {
        null
    }
    if (fast != null && fast.size >= CHIP_RECORD_BYTES) {
        return fast.copyOf(CHIP_RECORD_BYTES)
    }
    return try {
        val head = t.transceive(byteArrayOf(CMD_READ, HEADER_PAGE.toByte()))
        if (head.size < READ_BLOCK) return null
        val tail = t.transceive(byteArrayOf(CMD_READ, (HEADER_PAGE + 4).toByte()))
        if (tail.size < READ_BLOCK) return null
        val combined = ByteArray(CHIP_RECORD_BYTES)
        head.copyInto(combined, 0, 0, READ_BLOCK)
        tail.copyInto(combined, READ_BLOCK, 0, CHIP_RECORD_BYTES - READ_BLOCK)
        combined
    } catch (_: IOException) {
        null
    }
}

/** [readRecordPages] parsed as a КП record: the 16-byte КП code, or null. Never throws. */
internal fun readRecord(t: NfcTransport): ByteArray? = readRecordPages(t)?.let { parseChipRecord(it) }

/**
 * Write [code] (exactly [CHIP_CODE_BYTES] bytes) onto an Ultralight/NTAG tag as the raw header
 * record (magic `K24` + packed version/[type] byte + 16-byte code) over **NfcA** raw commands. We
 * talk NfcA directly rather than via `MifareUltralight.get()` because Android omits the
 * MifareUltralight tech for many NTAG/Ultralight chips (exposing only NfcA + Ndef), which would
 * otherwise be misread as "not an Ultralight tag". A chip carrying a `K24` record of another type is
 * refused ([writeRecordGuarded]). The write is header-last (commit marker) and is verified by a
 * read-back over the **same open connection** (see [writeRecord]). Blocking I/O — call off the main
 * thread. Never throws; returns a [ChipWriteResult].
 */
fun writeChipCode(tag: Tag, code: ByteArray, type: Int = CHIP_TYPE_KP): ChipWriteResult {
    require(code.size == CHIP_CODE_BYTES) { "code must be $CHIP_CODE_BYTES bytes" }
    val record = buildChipRecord(type, code)
    val nfcA = NfcA.get(tag) ?: return ChipWriteResult.Unsupported
    return try {
        nfcA.connect()
        writeRecordGuarded({ frame -> nfcA.transceive(frame) }, record)
    } catch (e: IOException) {
        ChipWriteResult.Failed(e.message ?: "Ошибка записи")
    } catch (_: SecurityException) {
        ChipWriteResult.Failed("Чип убран, приложите снова")
    } finally {
        closeQuietly(nfcA)
    }
}

/** NTAG/Ultralight READ — returns 16 bytes (4 pages) from the given page, wrapping past the end. */
private const val CMD_READ = 0x30.toByte()

/** One READ response: 4 pages. */
private const val READ_BLOCK = PAGE_SIZE * 4

/**
 * Read the КП [code] back from a tag provisioned by [writeChipCode], over **NfcA** raw READ — used
 * in reader mode (foreground), where the system NDEF dispatch is bypassed. Delegates the frame
 * sequencing (FAST_READ with plain-READ fallback) to [readRecord] and returns the 16-byte code for a
 * valid `K24` КП chip, or null for a foreign, blank, or non-КП tag. Signature `(tag) -> ByteArray?`
 * is preserved so scan/verify callers keep working. Blocking I/O — call off the main thread; never
 * throws.
 */
fun readChipCode(tag: Tag): ByteArray? {
    val nfcA = NfcA.get(tag) ?: return null
    return try {
        nfcA.connect()
        readRecord { frame -> nfcA.transceive(frame) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        closeQuietly(nfcA)
    }
}

/**
 * Read pages 4..8 once and decode both record types ([decodeChipPages]). Returns null when the tag
 * exposes no NfcA tech or the read fails. Blocking I/O — call off the main thread; never throws.
 */
fun readChipCodes(tag: Tag): ChipCodes? {
    val nfcA = NfcA.get(tag) ?: return null
    return try {
        nfcA.connect()
        readRecordPages { frame -> nfcA.transceive(frame) }?.let(::decodeChipPages)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        closeQuietly(nfcA)
    }
}
