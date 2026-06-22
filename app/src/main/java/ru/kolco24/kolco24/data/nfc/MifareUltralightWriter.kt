package ru.kolco24.kolco24.data.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Result of [writeChipCode]. Never thrown — surfaced as a value. */
sealed interface ChipWriteResult {
    /** All pages written and ACKed. */
    data object Success : ChipWriteResult

    /** Tag exposes no NfcA tech (not an ISO 14443-3A tag) — nothing written. */
    data object Unsupported : ChipWriteResult

    /** I/O error or NAK mid-write (tag moved away, write-protected, wrong chip, etc.). */
    data class Failed(val message: String) : ChipWriteResult
}

/** Bytes per MifareUltralight page. */
private const val PAGE_SIZE = 4

/** First user-memory page (pages 0–3 hold the UID/lock/OTP bytes — never written here). */
private const val USER_PAGE_START = 4

/** A code is one UUID = 16 bytes = 4 pages (4..7), present on every Ultralight variant. */
const val CHIP_CODE_BYTES = PAGE_SIZE * 4

// ---------------------------------------------------------------------------
// Raw on-chip format (header-first): page 4 = 'K' '2' '4' <packed>, pages 5–8 = 16-byte code.
// `packed = (version << 4) | type`; high nibble = version, low nibble = type. See docs/plans.
// Type/version constants are Int so the nibble math + the Int `type` param don't clash with Byte.
// ---------------------------------------------------------------------------

/** 24-bit magic 'K' '2' '4' (Kolco24 brand) — the "this is our chip" sentinel in page 4 bytes 0..2. */
val MAGIC = byteArrayOf(0x4B, 0x32, 0x34)

/** Low-nibble chip type: КП (checkpoint) — the only value written by this effort. */
const val CHIP_TYPE_KP = 0x1

/** Low-nibble chip type: participant — reserved/unused (future effort). */
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
 * Parse a raw chip record read from pages 4.. Returns the 16-byte КП code, or null when [pages] is
 * too short, the magic mismatches, the version nibble is not [CHIP_FORMAT_VERSION] (forward-incompat
 * guard), or the type nibble is not [CHIP_TYPE_KP] (КП-only reader). Trailing padding is tolerated.
 * Pure — no Android.
 */
fun parseChipRecord(pages: ByteArray): ByteArray? {
    if (pages.size < CHIP_RECORD_BYTES) return null
    for (i in MAGIC.indices) {
        if (pages[i] != MAGIC[i]) return null
    }
    val packed = pages[MAGIC.size].toInt() and 0xFF
    val version = (packed ushr 4) and 0x0F
    val type = packed and 0x0F
    if (version != CHIP_FORMAT_VERSION) return null
    if (type != CHIP_TYPE_KP) return null
    return pages.copyOfRange(PAGE_SIZE, PAGE_SIZE + CHIP_CODE_BYTES)
}

/**
 * Generate a fresh 16-byte chip code (a random UUID's big-endian bytes). The `code` is what the
 * legend crypto hashes into a `bid`; here it's just provisioned onto a blank tag.
 */
fun newChipCode(): ByteArray =
    ByteBuffer.allocate(CHIP_CODE_BYTES).apply {
        val uuid = UUID.randomUUID()
        putLong(uuid.mostSignificantBits)
        putLong(uuid.leastSignificantBits)
    }.array()

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
fun chipCodeFromHex(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }

private val HEX = "0123456789ABCDEF".toCharArray()

/** Ultralight / NTAG WRITE opcode — writes one 4-byte page: `[0xA2, page, b0, b1, b2, b3]`. */
private const val CMD_WRITE = 0xA2.toByte()

/** 4-bit ACK the tag returns for a successful WRITE (surfaced by NfcA as a single byte). */
private const val ACK = 0x0A.toByte()

/**
 * Write [code] (exactly [CHIP_CODE_BYTES] bytes) to an Ultralight/NTAG tag's user memory
 * (pages 4..7) over **NfcA** raw commands. We talk NfcA directly rather than via
 * `MifareUltralight.get()` because Android omits the MifareUltralight tech for many NTAG/Ultralight
 * chips (exposing only NfcA + Ndef), which would otherwise be misread as "not an Ultralight tag".
 * Blocking I/O — call off the main thread. Never throws; returns a [ChipWriteResult].
 */
fun writeChipCode(tag: Tag, code: ByteArray): ChipWriteResult {
    require(code.size == CHIP_CODE_BYTES) { "code must be $CHIP_CODE_BYTES bytes" }
    val nfcA = NfcA.get(tag) ?: return ChipWriteResult.Unsupported
    return try {
        nfcA.connect()
        for (i in 0 until CHIP_CODE_BYTES / PAGE_SIZE) {
            val page = USER_PAGE_START + i
            val from = i * PAGE_SIZE
            val frame = byteArrayOf(
                CMD_WRITE,
                page.toByte(),
                code[from], code[from + 1], code[from + 2], code[from + 3],
            )
            val response = nfcA.transceive(frame)
            if (response.isEmpty() || response[0] != ACK) {
                return ChipWriteResult.Failed("Метка отклонила запись страницы $page")
            }
        }
        ChipWriteResult.Success
    } catch (e: IOException) {
        ChipWriteResult.Failed(e.message ?: "Ошибка записи")
    } finally {
        try {
            nfcA.close()
        } catch (_: IOException) {
        }
    }
}

/** External-type record carrying the checkpoint code bytes (type `kolco24.ru:cp`). */
private const val NDEF_DOMAIN = "kolco24.ru"
private const val NDEF_TYPE = "cp"

/** NDEF TLV markers in the tag's user memory: `0x03 <len> <message…> 0xFE`. */
private const val TLV_NDEF: Byte = 0x03
private const val TLV_TERMINATOR = 0xFE.toByte()

/**
 * Write [code] to an Ultralight/NTAG tag as a proper **NDEF message** over NfcA raw WRITE: an
 * external-type record holding the 16 code bytes plus a trailing **Android Application Record**
 * for [packageName], so the tag stays NDEF-formatted (readable by NFC Tools) and scanning it with
 * the app closed auto-opens the app.
 *
 * Layout from page 4: TLV `0x03 <len> <ndef> 0xFE`, zero-padded to a page boundary. The Capability
 * Container (page 3) is left untouched — assumes the tag was already NDEF-formatted (true for our
 * chips; we only ever overwrote pages 4+). Blocking I/O — call off the main thread. Never throws.
 */
fun writeChipCodeNdef(tag: Tag, code: ByteArray, packageName: String): ChipWriteResult {
    require(code.size == CHIP_CODE_BYTES) { "code must be $CHIP_CODE_BYTES bytes" }
    val message = NdefMessage(
        arrayOf(
            NdefRecord.createExternal(NDEF_DOMAIN, NDEF_TYPE, code),
            // AAR must be the last record so Android treats it as the dispatch hint.
            NdefRecord.createApplicationRecord(packageName),
        ),
    )
    val ndef = message.toByteArray()
    if (ndef.size >= 0xFF) return ChipWriteResult.Failed("NDEF слишком большой")
    val tlv = byteArrayOf(TLV_NDEF, ndef.size.toByte()) + ndef + byteArrayOf(TLV_TERMINATOR)
    val padded = tlv + ByteArray((PAGE_SIZE - tlv.size % PAGE_SIZE) % PAGE_SIZE)

    val nfcA = NfcA.get(tag) ?: return ChipWriteResult.Unsupported
    return try {
        nfcA.connect()
        for (i in 0 until padded.size / PAGE_SIZE) {
            val page = USER_PAGE_START + i
            val from = i * PAGE_SIZE
            val frame = byteArrayOf(
                CMD_WRITE,
                page.toByte(),
                padded[from], padded[from + 1], padded[from + 2], padded[from + 3],
            )
            val response = nfcA.transceive(frame)
            if (response.isEmpty() || response[0] != ACK) {
                return ChipWriteResult.Failed("Метка отклонила запись страницы $page")
            }
        }
        ChipWriteResult.Success
    } catch (e: IOException) {
        ChipWriteResult.Failed(e.message ?: "Ошибка записи")
    } finally {
        try {
            nfcA.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * The external record's type bytes as written by [NdefRecord.createExternal]: `domain:type`,
 * lower-cased ASCII (`kolco24.ru:cp`). The manifest `NDEF_DISCOVERED` filter matches the same
 * value via the `vnd.android.nfc://ext/$NDEF_DOMAIN:$NDEF_TYPE` URI.
 */
private val NDEF_EXTERNAL_TYPE = "$NDEF_DOMAIN:$NDEF_TYPE".toByteArray(Charsets.US_ASCII)

/**
 * Extract the chip [code] (16 bytes) from the NDEF [messages] delivered in an NFC launch intent —
 * i.e. the external-type record written by [writeChipCodeNdef]. Ignores the trailing AAR and any
 * other records. Returns null if no matching, correctly-sized record is present.
 */
fun chipCodeFromNdef(messages: Array<NdefMessage>): ByteArray? {
    for (message in messages) {
        for (record in message.records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                record.type.contentEquals(NDEF_EXTERNAL_TYPE) &&
                record.payload.size == CHIP_CODE_BYTES
            ) {
                return record.payload
            }
        }
    }
    return null
}

/** NTAG/Ultralight READ — returns 16 bytes (4 pages) from the given page, wrapping past the end. */
private const val CMD_READ = 0x30.toByte()

/** One READ response: 4 pages. */
private const val READ_BLOCK = PAGE_SIZE * 4

/**
 * Read a chip [code] back from a tag written by [writeChipCodeNdef], over NfcA raw READ — used in
 * reader mode (foreground), where the system NDEF dispatch is bypassed so the launch-intent path
 * doesn't fire. Reads the NDEF TLV at page 4, parses the message, and returns the embedded code,
 * or null if the tag carries no valid kolco24 code. Blocking I/O — call off the main thread;
 * never throws.
 */
fun readChipCode(tag: Tag): ByteArray? {
    val nfcA = NfcA.get(tag) ?: return null
    return try {
        nfcA.connect()
        // First block from page 4 holds the TLV header: 0x03 <len> ...
        val head = nfcA.transceive(byteArrayOf(CMD_READ, USER_PAGE_START.toByte()))
        if (head.size < READ_BLOCK || head[0] != TLV_NDEF) return null
        val len = head[1].toInt() and 0xFF
        if (len == 0 || len >= 0xFF) return null // empty, or long-form TLV we never write
        val total = 2 + len // TLV header (0x03 + len byte) + NDEF message bytes
        val buffer = ByteArray(((total + READ_BLOCK - 1) / READ_BLOCK) * READ_BLOCK)
        head.copyInto(buffer, 0, 0, READ_BLOCK)
        var offset = READ_BLOCK
        var page = USER_PAGE_START + 4
        while (offset < total) {
            val block = nfcA.transceive(byteArrayOf(CMD_READ, page.toByte()))
            if (block.size < READ_BLOCK) return null
            block.copyInto(buffer, offset, 0, READ_BLOCK)
            offset += READ_BLOCK
            page += 4
        }
        chipCodeFromNdef(arrayOf(NdefMessage(buffer.copyOfRange(2, total))))
    } catch (_: IOException) {
        null
    } catch (_: FormatException) {
        null
    } finally {
        try {
            nfcA.close()
        } catch (_: IOException) {
        }
    }
}
