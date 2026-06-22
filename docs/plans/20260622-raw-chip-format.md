# Raw chip format (header-first) + drop NDEF cold-launch

## Overview
Migrate КП (checkpoint) chip provisioning and reading from the current NDEF format to a **raw
MifareUltralight/NTAG format with a small header**, and remove the NDEF cold-launch path entirely.

Problem it solves:
- The NDEF code is already **discarded on cold launch** for anti-cheat (intent extras are
  attacker-controllable — see `handleNfcIntent`), so NDEF's only surviving benefit is auto-open.
- Raw reads/writes are faster and **more robust on a short tap** (1 transceive via FAST_READ vs ~5
  NDEF READs; ~4 page writes vs ~20), which matters at a КП where a chip is tapped briefly.
- A **unified on-chip format** with a type byte seeds future participant chips (type=0x02) using the
  same provision/read mechanism.

Trade-off accepted (settled in brainstorm): we **lose cold/warm-launch auto-open** (only NDEF+AAR
gives it). After this change the «Отметить КП» overlay opens only via the live reader-mode path
(app open/foreground) and the orange FAB. This is the deliberate cost of going raw-only.

## Context (from discovery)
- **Files/components involved:**
  - `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt` — `writeChipCode`
    (raw, exists), `writeChipCodeNdef` (remove), `readChipCode` (rewrite raw + FAST_READ/fallback),
    `chipCodeFromNdef` (remove), `newChipCode`/`chipCodeHex`/`chipCodeFromHex` (keep). Add format
    constants + pure build/parse helpers + a GET_VERSION reader.
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — remove `handleNfcIntent` NDEF path +
    cold/warm `CapturedScan(openOnly = true)`; the live idle `onTagDiscovered` path (reads code via
    `readChipCode`) stays; debug chip-write flow drops `chipWriterNdef` (NDEF mode gone).
  - `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt` — switch the
    `writeChipCodeNdef(tag, bytes, APPLICATION_ID)` call (line ~270) to the new raw header write.
  - `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt` — drop the NDEF debug row
    (`onWriteChipNdef`), add a "Инфо о чипе" (GET_VERSION) debug row.
  - `app/src/main/AndroidManifest.xml` — remove the `NDEF_DISCOVERED` intent filter (lines ~38–47).
  - `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt` — **unchanged** (see decision below).
  - **`readChipCode` consumers (no edit — signature `(tag) -> ByteArray?` is preserved, so they work
    unchanged on raw chips):** `MainActivity.kt` live idle `onTagDiscovered` + the in-overlay `Live`
    scan path (~line 818), and `app/src/main/java/ru/kolco24/kolco24/ui/admin/CheckChipScreen.kt`
    (~line 173, the admin «Проверка чипов КП» flow — `readChipCode` → `LegendCrypto.bid`). Listed so
    Post-Completion re-verifies them against a raw-format chip.
- **Related patterns found:**
  - `ChipWriteResult` sealed result type; all NFC I/O is blocking, off-main, never-throws.
  - Pure helpers (`newChipCode`/`chipCodeHex`/`chipCodeFromHex`) JVM-tested by
    `MifareUltralightWriterTest`; the thin NfcA adapter (real connect/transceive/close) is untested
    (hardware), per repo convention — but the command sequencing is testable via the `NfcTransport`
    seam introduced in Task 3.
  - `classifyTag(code: ByteArray?, …)` already treats a non-null code as a КП chip and a null code
    as a bracelet (uid lookup in `bindings`).
- **Dependencies identified:** none in the data layer. No Room migration (chip format is on-tag, not
  in the DB). Legend `bid` resolution and `LegendCrypto` are unchanged — `bid = sha256(code)[:16]`
  still consumes the same 16-byte code.
- **No NDEF chips in the field → no migration, no NDEF-read fallback.** The NDEF write path
  (`writeChipCodeNdef`) has **never provisioned a production КП chip** — provisioning is admin-only and
  no chips have been written/deployed yet, so there is no installed base of NDEF-formatted chips to
  read after this change. Dropping the NDEF parser from `readChipCode` therefore strands nothing. We
  deliberately add **no** NDEF-read fallback and **no** dual-format support: every chip is (re)written
  in the new raw `K24` format at provisioning time. (Any chip written during dev/testing is simply
  re-provisioned — a no-op concern.)

### Key simplification discovered (reduces churn vs. brainstorm framing)
`classifyTag` and the `CapturedScan`/`ScanInput`/`onScanTag` plumbing **do not need a type
parameter** for this effort. Participant chips (type=0x02) are out of scope, so today every
provisioned chip is type=0x01 (КП). The magic + type validation lives **inside `readChipCode`**,
which keeps its `(tag) -> ByteArray?` signature: it returns the 16-byte КП code for a valid КП chip,
and `null` for foreign, blank, or non-КП (future participant) chips. Therefore:
- `ScanSession.kt` (`classifyTag`/`reduce`) is **untouched**; `ScanSessionTest`/`ScanTagDecisionTest`
  stay green with no edits.
- A future participant chip (type=0x02) tapped at a КП reads as `null` code → falls through to the
  existing uid-based bracelet lookup, which is exactly correct while participants remain uid-bound.

## Development Approach
- **Testing approach**: Regular (code first, then tests) — consistent with the repo and the prior
  scan plan. Test targets: the **pure** format build/parse + version-parse helpers, **and** the
  command-sequencing logic (`writeRecord`/`readRecord`) driven through the `NfcTransport` seam with a
  fake (P2). Only the **thin NfcA adapter** (the real `connect`/`transceive`/`close`) stays
  hardware-bound and untested.
- Complete each task fully before the next; small, focused changes.
- **Every code task includes tests where there is pure/unit-testable logic.** Compose hosts / Activity
  wiring and the thin NfcA adapter stay untested (repo convention); the pure helpers **and the
  transport-driven write/read sequencing** are the test targets.
- All tests must pass before starting the next task. Run after each change.
- Maintain backward compatibility where it still applies: `writeChipCode` callers, the live
  reader-mode open path, `classifyTag`, and existing tests must keep working.

## Testing Strategy
- **Unit tests** (extend `MifareUltralightWriterTest`):
  - `buildChipRecord(type, code)`: produces 3-byte magic `K24` + packed (`version<<4 | type`) byte +
    16-byte code; length, byte-vector, and packed-byte (e.g. 0x11) assertions; rejects wrong-size
    code (`require`).
  - `parseChipRecord(pages)`: valid КП record → code; wrong magic (any of the 3 bytes) → null;
    all-zero (blank) → null; too-short buffer → null; future type nibble (0x2) → null (КП-only
    reader); **unknown version nibble (e.g. 0x2) → null** (forward-incompat guard); trailing padding
    tolerated.
  - `chipModelFromVersion(resp)`: NTAG213 vector (`00 04 04 02 01 00 0F 03`) → "NTAG213"; Ultralight
    product byte (0x03) → labelled accordingly; short/empty response → "неизвестно" (never throws).
  - round-trip: `parseChipRecord(buildChipRecord(KP, code)) == code`.
  - **`writeRecord`/`readRecord` over a fake `NfcTransport`** (P2 — the critical new sequencing
    logic): write-frame order (invalidate page 4 → code pages 5–8 → header page 4 last); non-ACK WRITE
    → `Failed`; `readRecord` FAST_READ happy path; FAST_READ fallback on **both** `IOException` **and**
    a short/1-byte NAK (< 20 bytes); fallback never-throws on a short/NAK second READ or `IOException`
    on either READ (→ `null`); read-back mismatch → `Failed`. This makes the header-last/commit-marker
    + fallback logic regression-safe (the P1 bugs lived here), so it is **not** left to manual testing.
- **e2e tests**: none in this project. Physical-chip verification is manual (Post-Completion).
- Build/lint/test gates: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `./gradlew lintDebug`.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- New tasks get a ➕ prefix; blockers get a ⚠️ prefix.
- Update this plan if implementation deviates from scope.

## Solution Overview

**On-chip raw format (header-first):**
```
Page 4:    'K' '2' '4' <packed>          // 24-bit magic 0x4B 0x32 0x34 + packed version/type byte
Pages 5–8: 16-byte code (UUID)           // unchanged — bid = sha256(code)[:16]

packed byte = (version << 4) | type      // high nibble = version, low nibble = type
  type:    0x1 = КП (checkpoint)         // only value written by this effort
           0x2 = participant             // reserved, future effort
  version: 0x1
e.g. version 1 + type КП → packed = 0x11
```
- Magic `K24` (ASCII "Kolco24", `0x4B 0x32 0x34`) replaces NDEF's external-type as the **"this is our
  chip"** signal. Raw-only loses that signal otherwise — any tag with 16 bytes in pages 4–7 would read
  as a code. Magic is validated first so foreign/blank chips are rejected (in one READ on the fallback
  path). The `24` is the **brand** (Kolco24), not a format version — versioning lives solely in the
  packed byte's version nibble, so there is one source of truth for the format version.
- **Packed version/type byte:** type and version each get one nibble (16 values — ample: 2 types, a
  handful of versions ever), freeing the third header byte for the 24-bit brand magic. Same 4-byte
  header (one page), no extra page. Unpack: `version = (b ushr 4) and 0x0F`, `type = b and 0x0F`.
- Magic is a **sanity sentinel, not a security control** (an attacker can write any bytes incl. the
  magic) — 24 vs 16 bits is a marginal collision-resistance gain against accidental blank/foreign
  chips, chosen mainly because `K24` reads as the brand; both widths reject non-our chips with
  overwhelming probability.
- Header-first chosen so the **version is read before the body** (a future format bump is detected
  before mis-parsing) and blank/foreign chips reject on the first READ. The version nibble is
  **enforced**: `parseChipRecord` rejects any `version != CHIP_FORMAT_VERSION`, so a future
  incompatible layout is never mis-read as the current one (the version field would be meaningless
  otherwise).

**Reading** (`readChipCode` rewrite): try **FAST_READ** (`0x3A 04 08` → pages 4–8 = 20 bytes in one
transceive; NTAG21x/Ultralight EV1), **fall back to plain READ ×2** when FAST_READ NAKs/throws — so
chip-model uncertainty (likely NTAG213, unconfirmed) is a non-issue. The fallback is **READ page 4**
(`0x30 04` → pages 4–7 = bytes 0–15) **+ READ page 8** (`0x30 08` → pages 8–11; first 4 bytes =
record bytes 16–19), concatenate the first 20 bytes. Then validate magic → check `type == КП` →
return the 16-byte code, else `null`. (A single `0x30` READ returns 4 pages and wraps, so the second
READ must start at page **8**, not 7, to cover the last code page.)

**Writing** (provisioning + debug): `writeChipCode` writes the new header-format record (24-bit magic
`K24` + packed `version<<4 | type` byte with type=КП + 16-byte code) to pages 4–8 over raw NfcA `0xA2`
WRITE (extends the existing 4-page write to 5 pages). `writeChipCodeNdef` is removed.

**Header-last write order (commit-marker — P1 safety):** a per-page `0xA2` WRITE is atomic, but the
5-page record is **not**, so a short tap mid-write could leave a valid header (page 4) over a
half-written code → the chip would be **recognized with a corrupted code**. To make the header a
commit marker, `writeChipCode` writes in this order:
1. **Invalidate page 4** — write a non-magic value (e.g. all-zero) first. Essential when
   re-provisioning a chip that already carries a valid header: it stops the old magic from validating
   a chip whose code is being overwritten in place.
2. **Write the code** — pages 5, 6, 7, 8 (record bytes 4..19).
3. **Write the valid header last** — page 4 (record bytes 0..3: magic + packed byte).

An interruption before step 3 leaves page 4 non-magic → `parseChipRecord` rejects the chip
(re-provision needed) — never a corrupted-but-accepted code. After writing, `writeChipCode`
**reads the record back over the same open NfcA connection** (not a second `connect()` — see the P1
note in Technical Details) and returns `Failed` unless `parseChipRecord` yields exactly the written
code, so a partial write surfaces at provision time instead of in the field.

**GET_VERSION** debug readout: `0x60` → 8-byte response → `chipModelFromVersion` → human label, shown
in a debug-only Settings row for one-time verification of the physical stock.

### Key design decisions & rationale
- **Keep the 16-byte code.** `bid` derivation and the server `code` contract are unchanged; only the
  on-tag wrapping changes. No crypto/DB impact.
- **Magic+packed-byte parsing inside `readChipCode`, not in `classifyTag`.** Minimal churn; existing
  pure scan tests stay green; participant handling is cleanly deferred (see "Key simplification" above).
- **FAST_READ with behavioral fallback** rather than `GET_VERSION`-gated branching — simpler, one
  code path, robust on any chip.
- **Header-last commit-marker write + read-back verify** (see "Writing" above) so an interrupted tap
  can never produce a chip that validates with a corrupted code.
- **No Room migration** — the format lives on the tag.

## Technical Details
- New constants in `MifareUltralightWriter.kt`: `MAGIC = byteArrayOf(0x4B, 0x32, 0x34)` ('K','2','4'),
  `CHIP_TYPE_KP = 0x1`, `CHIP_TYPE_PARTICIPANT = 0x2` (reserved/unused, low nibble),
  `CHIP_FORMAT_VERSION = 0x1` (high nibble), `HEADER_PAGE = 4`, `CODE_PAGE_START = 5`, record length =
  `PAGE_SIZE + CHIP_CODE_BYTES` = 20 bytes (5 pages, 4..8). **Type/version constants are `Int`** (not
  `Byte`) so nibble math and the `Byte` `type` param don't clash (P2); the packed byte is computed
  once and converted: `(((CHIP_FORMAT_VERSION shl 4) or type) and 0xFF).toByte()`.
- `CMD_FAST_READ = 0x3A`; FAST_READ frame `byteArrayOf(0x3A, 0x04, 0x08)` → 20 bytes. READ fallback
  reuses the existing `CMD_READ = 0x30` (kept) reading **page 4** then **page 8** and concatenating
  the first 20 bytes (page-4 READ = bytes 0–15, page-8 READ = bytes 16–19 from its first page).
- `CMD_GET_VERSION = 0x60`; GET_VERSION frame `byteArrayOf(0x60)` → 8 bytes. Product type at byte 2
  (0x04=NTAG, 0x03=Ultralight), storage size at byte 6 (0x0F=NTAG213, 0x11=215, 0x13=216).
- Pure helpers (JVM-testable, no Android):
  - `fun buildChipRecord(type: Int, code: ByteArray): ByteArray` — `require(code.size == CHIP_CODE_BYTES)`
    and `require(type in 0..15)` (nibble); returns `MAGIC (3 bytes) + packed byte (version<<4 | type) +
    code` (20 bytes). `type: Int` matches the `Int` constants (P2).
  - `fun parseChipRecord(pages: ByteArray): ByteArray?` — null unless `pages` ≥ 20, the 3 magic bytes
    match, the packed byte's high nibble (`version`) `== CHIP_FORMAT_VERSION`, **and** its low nibble
    (`type`) `== CHIP_TYPE_KP`; else returns `pages[4..19]` (the code). An unknown version → null (the
    chip is treated as unreadable until a future reader that understands that version ships).
  - `fun chipModelFromVersion(resp: ByteArray): String` — maps the 8-byte response to a label;
    never throws.
- **Transport seam (testability — P2).** Extract a tiny `fun interface NfcTransport { fun
  transceive(frame: ByteArray): ByteArray }` so the command sequencing is JVM-testable with a fake
  (no NfcA hardware). The frame-level logic lives in transport-driven functions:
  - `internal fun writeRecord(t: NfcTransport, record: ByteArray): ChipWriteResult` — issues the
    **header-last** `0xA2` WRITE frames in order (see below), ACK-checks each, then calls
    `readRecord(t)` **on the same transport** and returns `Failed` unless the parsed code equals
    `record[4..19]`.
  - `internal fun readRecord(t: NfcTransport): ByteArray?` — FAST_READ frame; **FAST_READ counts as
    failed on `IOException` OR any response shorter than 20 bytes** (a tag may answer an unsupported
    command with a 1-byte NAK rather than throwing — passing that straight to `parseChipRecord` would
    return null instead of falling back; P1). On FAST_READ failure, fall back to **READ page 4 + READ
    page 8**: each `0x30` READ must return **≥ `READ_BLOCK` (16) bytes** — a short/NAK response or an
    `IOException` on **either** READ → return `null` (never throw, never index past a short buffer;
    P2). On success, concatenate the first 20 bytes → `parseChipRecord`.
- I/O wrappers (blocking, off-main, never-throw, return `ChipWriteResult`/`ByteArray?`) — thin NfcA
  adapters over the seam:
  - `writeChipCode(tag, code)` — build `buildChipRecord(CHIP_TYPE_KP, code)`, open **one** NfcA
    connection, wrap it as an `NfcTransport`, call `writeRecord(t, record)` (which writes **and**
    reads back over that **same open connection** — never a second `connect()`), close in `finally`.
    Write order: (1) WRITE page 4 = invalid (all-zero) header; (2) WRITE pages 5–8 = record bytes
    4..19 (code); (3) WRITE page 4 = record bytes 0..3 (valid magic+packed) commit marker.
    A NAK/`IOException` → `Failed`.
    > **P1 fix:** the read-back must reuse the writer's already-open NfcA, **not** call the public
    > `readChipCode(tag)` (which would `connect()` a second time to the same `Tag` — only one
    > `TagTechnology` connection is allowed at a time, so it would always fail). `writeRecord` reading
    > via the shared transport guarantees a single connection.
  - `readChipCode(tag)` — open NfcA, wrap as transport, `readRecord(t)`, close. (Public entry for the
    scan/verify read paths.)
  - `readChipVersion(tag): ByteArray?` — connects NfcA, sends GET_VERSION, returns 8 bytes or null.

## What Goes Where
- **Implementation Steps** (`[ ]`): all code + the pure-helper tests, build/lint/test gates.
- **Post-Completion** (no checkboxes): physical-chip verification (provision, read-back, foreign/blank
  rejection, GET_VERSION model confirm, cold-launch-no-longer-opens), which cannot be automated here.

## Implementation Steps

### Task 1: Format constants + pure build/parse helpers (+ tests)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriterTest.kt`

- [x] add format constants as **`Int`** (`MAGIC = byteArrayOf(0x4B, 0x32, 0x34)`, `CHIP_TYPE_KP = 0x1`,
      `CHIP_TYPE_PARTICIPANT = 0x2`, `CHIP_FORMAT_VERSION = 0x1`, `HEADER_PAGE`, `CODE_PAGE_START`) to
      `MifareUltralightWriter.kt` — Int (not Byte) so the packed nibble math and the `Int` `type` param
      line up without literal-type clashes (P2)
- [x] add pure `buildChipRecord(type: Int, code: ByteArray): ByteArray` (require 16-byte code +
      `type in 0..15`; 3-byte magic + packed `(((version shl 4) or type) and 0xFF).toByte()` + code =
      20 bytes)
- [x] add pure `parseChipRecord(pages: ByteArray): ByteArray?` (validate length + 3 magic bytes +
      high-nibble `version == CHIP_FORMAT_VERSION` + low-nibble `type == КП`; return the 16-byte code
      or null — any mismatch incl. unknown version → null)
- [x] write tests: `buildChipRecord` byte-vector + packed-byte (0x11) + length + wrong-size `require` +
      out-of-nibble-range `type` (e.g. 16) `require`
- [x] write tests: `parseChipRecord` valid КП → code; wrong magic → null; all-zero → null; short → null;
      future type nibble 0x2 → null; **unknown version nibble (0x2) → null**; trailing padding
      tolerated; round-trip with `buildChipRecord`
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 2: GET_VERSION reader + pure model parser (+ tests)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriterTest.kt`

- [x] add pure `chipModelFromVersion(resp: ByteArray): String` (NTAG213/215/216 + Ultralight labels;
      short/empty → "неизвестно"; never throws)
- [x] add `readChipVersion(tag: Tag): ByteArray?` NfcA I/O wrapper (connect, `0x60`, 8 bytes or null;
      blocking, never throws)
- [x] write tests: NTAG213 vector → "NTAG213"; Ultralight product byte → label; empty/short → "неизвестно"
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: Rewrite `writeChipCode` + `readChipCode` to raw header format (keep NDEF funcs for now)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriterTest.kt`

- [ ] add the `fun interface NfcTransport { fun transceive(frame: ByteArray): ByteArray }` seam (P2
      testability) and `CMD_FAST_READ = 0x3A`
- [ ] add `internal fun writeRecord(t: NfcTransport, record: ByteArray): ChipWriteResult` — issue the
      **header-last** `0xA2` WRITE frames, ACK-checked per frame: (1) page 4 ← invalid all-zero header;
      (2) pages 5–8 ← record bytes 4..19 (code); (3) page 4 ← record bytes 0..3 (valid header) commit
      marker; then call `readRecord(t)` **on the same transport** and return `Failed` unless the parsed
      code equals `record[4..19]`
- [ ] add `internal fun readRecord(t: NfcTransport): ByteArray?` — FAST_READ `0x3A 04 08`; treat
      **`IOException` OR a response < 20 bytes** (e.g. a 1-byte NAK) as FAST_READ failure → fall back
      (P1). Fallback = READ page 4 + READ page 8 (`0x30`); **each must return ≥ `READ_BLOCK` (16)
      bytes** — a short/NAK response or `IOException` on either READ → return `null` without throwing
      or indexing past the buffer (P2). On success concatenate the first 20 bytes → `parseChipRecord`.
      **No NDEF-read fallback** — no installed base of NDEF chips (Context → "No NDEF chips in the
      field"); raw `K24` only. **Keep** `CMD_READ = 0x30`, `READ_BLOCK`, `PAGE_SIZE`/`USER_PAGE_START`
- [ ] rewrite `writeChipCode(tag, code)` as a thin adapter: build the record, open **one** NfcA
      connection, wrap as `NfcTransport`, call `writeRecord(t, record)` (writes **and** reads back over
      that same open connection), close in `finally`. **P1 fix:** the read-back must NOT call the public
      `readChipCode(tag)` — a second `connect()` to the same `Tag` is disallowed and would always fail
- [ ] rewrite `readChipCode(tag)` as a thin adapter: open NfcA, wrap as `NfcTransport`, `readRecord(t)`,
      close. Signature `(tag) -> ByteArray?` unchanged, so scan/verify callers keep working
- [ ] **leave `writeChipCodeNdef` / `chipCodeFromNdef` (+ their NDEF/TLV helpers and imports) in place
      for now** — unused by the new `readChipCode` but still called by ProvisioningScreen and
      MainActivity; deleting here would break the build. Removal is the final step of Task 6, after
      every caller is migrated (Tasks 4–6)
- [ ] write JVM tests with a **fake `NfcTransport`** (records frames sent, returns canned responses) —
      P2 coverage of the critical new logic:
      - `writeRecord` issues frames in order **invalidate page 4 → pages 5–8 → header page 4** (assert
        the recorded frame sequence + that the page-4 header frame is last)
      - a non-ACK response on any WRITE → `ChipWriteResult.Failed` (no further frames sent)
      - `readRecord` happy path: fake returns a valid 20-byte FAST_READ → parsed code
      - FAST_READ fallback triggers on **both** failure modes: FAST_READ throwing `IOException`
        **and** FAST_READ returning a **short/1-byte NAK** (< 20 bytes) → both fall back to the two
        `0x30` READs and still parse (P1)
      - fallback robustness (P2 / never-throws): a **short or NAK second READ** (< 16 bytes) → `null`,
        no exception; an `IOException` on the page-4 READ and on the page-8 READ → `null`
      - read-back mismatch (fake returns a different/zeroed record) → `writeRecord` returns `Failed`
- [ ] `./gradlew testDebugUnitTest` green (incl. the new transport tests); `./gradlew assembleDebug`
      green (signatures unchanged; NDEF funcs still present for their callers)

### Task 4: Switch provisioning to raw write

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt`

- [ ] replace the `writeChipCodeNdef(tag, bytes, BuildConfig.APPLICATION_ID)` call (~line 270) with
      `writeChipCode(tag, bytes)`; drop the now-unused `writeChipCodeNdef` import; remove the
      orphaned `BuildConfig` import if `APPLICATION_ID` was its only use
- [ ] update the КП recognition KDoc/comment that references `writeChipCodeNdef` (~line 99)
- [ ] (no unit tests — Compose host, untested per convention)
- [ ] `./gradlew assembleDebug` compiles **green** (the now-unused `writeChipCodeNdef` still exists —
      it is removed in Task 6 after its last caller goes)

### Task 5: Remove NDEF cold/warm launch path in MainActivity + manifest

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] remove `handleNfcIntent` and its `onCreate`/`onNewIntent` calls, the `ndefMessagesOf` helper,
      and the `chipCodeFromNdef` + `android.nfc.NdefMessage` imports; the cold/warm
      `CapturedScan(openOnly = true)` publish goes away
- [ ] remove `CapturedScan.openOnly` (its only producer was `handleNfcIntent`; the live-idle producer
      at `onTagDiscovered:354` already uses the default `false`). Simplify the two dispatcher guards
      that special-cased it — `!scan.openOnly` (~line 634) and `if (!scan.openOnly)` (~line 646) —
      to unconditional. **Keep** the `pendingScan` narrow-race buffering at ~634 and the live-idle
      producer (both still needed). **Keep** the `SelectedTeamState.Loading` deferral branch (~656–659)
      and the `teamState`-keyed dispatcher `LaunchedEffect` (~619) — now reachable only via live-idle
      during a team switch; add a one-line comment noting the cold-launch race it originally solved is
      gone but the branch stays as a defensive guard
- [ ] update stale KDoc/comments that name "warm onNewIntent / cold onCreate / NDEF launch":
      `CapturedScan` KDoc (~370, 375), dispatcher comments (~613, 624, 630, 644–645)
- [ ] remove the `NDEF_DISCOVERED` `<intent-filter>` from `AndroidManifest.xml` (lines ~38–47); keep
      the NFC permission, `uses-feature`, and reader-mode infra. **Reader-mode flags
      (incl. `FLAG_READER_SKIP_NDEF_CHECK`, ~358–363) are intentionally unchanged** — we read raw NfcA
- [ ] reconsider `android:launchMode="singleTop"` — it backed `onNewIntent` NDEF re-delivery; note in
      a comment whether it's kept (harmless) or removed; leaving it is acceptable
- [ ] (no unit tests — Activity/manifest, untested per convention)
- [ ] `./gradlew assembleDebug` compiles; `./gradlew lintDebug` clean

### Task 6: Debug surface (drop NDEF write row, add "Инфо о чипе") + delete dead NDEF API

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/nfc/MifareUltralightWriter.kt`

- [ ] in `MainActivity`: remove `chipWriterNdef` state and the `onWriteChipNdef` wiring + the
      `if (chipWriterNdef) writeChipCodeNdef(...) else writeChipCode(...)` branch (now always raw write)
- [ ] add a **new** `@Volatile var onTagForChipInfo: ((Tag) -> Unit)? = null` hook (mirrors the
      existing `onTagFor*` hooks; CLAUDE.md convention = each `DisposableEffect` owns exactly one hook)
      and insert it into the `onTagDiscovered` priority ladder (~315–341) — place it **before**
      `onTagForWrite` (it and the write dialog are never armed together, but a distinct hook avoids the
      `onTagForWrite` collision the reviewer flagged); document its position
- [ ] add a debug-only `onReadChipInfo` flow armed via a `DisposableEffect` that sets/clears
      `onTagForChipInfo`, calls `readChipVersion(tag)` off-main, and surfaces `chipModelFromVersion(...)`
      in a dialog/snackbar. DEBUG-only, mirroring the existing debug chip-write flow
- [ ] in `SettingsScreen`: remove the `onWriteChipNdef` param + its `DebugRow`; add an
      `onReadChipInfo: (() -> Unit)? = null` param + a "Инфо о чипе" `DebugRow` (DEBUG builds only)
- [ ] **now that the last caller is gone** (ProvisioningScreen in Task 4, `handleNfcIntent` in Task 5,
      the debug writer above), delete the dead NDEF API from `MifareUltralightWriter.kt`:
      `writeChipCodeNdef`, `chipCodeFromNdef`, the NDEF/TLV helpers (`NDEF_DOMAIN`, `NDEF_TYPE`,
      `TLV_*`, `NDEF_EXTERNAL_TYPE`), the `catch (_: FormatException)` in the old read path, and the
      imports `android.nfc.FormatException`/`NdefMessage`/`NdefRecord`. **Keep** `CMD_READ`/`READ_BLOCK`/
      `PAGE_SIZE`/`USER_PAGE_START` (READ fallback uses them). `grep` confirms no references remain
- [ ] (no unit tests — Compose host + NfcA I/O, untested per convention)
- [ ] `./gradlew assembleDebug` compiles; `./gradlew lintDebug` clean (no unused-symbol warnings)

### Task 7: Verify acceptance criteria
- [ ] `classifyTag`/`ScanSession.kt` confirmed untouched; `ScanSessionTest`/`ScanTagDecisionTest` green
- [ ] КП provisioning writes the raw header record; reader-mode `readChipCode` reads it back to the
      same code (covered by `buildChipRecord`/`parseChipRecord` round-trip unit test)
- [ ] foreign/blank chip → `parseChipRecord` returns null (unit test) → reader-mode ignores it
- [ ] write-order (invalidate → code → header-last), ACK/NAK, FAST_READ fallback, and read-back
      mismatch covered by the fake-`NfcTransport` unit tests (Task 3); a forced physical partial write
      additionally confirmed manually (Post-Completion)
- [ ] no remaining references to `writeChipCodeNdef`/`chipCodeFromNdef`/`NDEF_DISCOVERED`
      (`grep` clean)
- [ ] run full suite: `./gradlew testDebugUnitTest`
- [ ] `./gradlew assembleDebug` and `./gradlew lintDebug` pass

### Task 8: [Final] Docs + plan move
- [ ] update `CLAUDE.md`: `MifareUltralightWriter.kt` bullet (raw header format, FAST_READ/fallback,
      GET_VERSION, NDEF functions removed), the NFC reader-mode infra section (drop `NDEF_DISCOVERED`,
      `handleNfcIntent`, cold/warm auto-open, `chipWriterNdef`), the chip-scan-open section (live-idle
      only), and the `SettingsScreen` debug-row description
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification (physical NFC required, cannot be automated here):**
- Provision a КП chip via the admin provisioning flow → confirm `ChipWriteResult.Success`.
- Read it back in reader mode (app foreground): tap at the КП → «Отметить КП» opens with the КП
  identified and scoring proceeds (КП + member bracelets complete the take).
- Tap a foreign/blank NFC tag while idle → nothing happens (magic rejects it).
- **Interrupted write (header-last safety):** start provisioning a chip and pull it away mid-write,
  a few times at different moments. **The only forbidden outcome is a chip recognized with a
  mixed/corrupted code.** Two outcomes are both acceptable (the timing decides which):
  - interrupted **before** the final header write → page 4 non-magic → chip **not** recognized in
    reader mode; `writeChipCode` returns `Failed`; re-tapping re-provisions cleanly; **or**
  - interrupted **after** the final header write but **before/at** read-back → the chip is fully
    valid and **is** recognized correctly, even though `writeChipCode` may return `Failed` (read-back
    didn't complete). That `Failed` is harmless — the admin re-taps and provisioning rewrites the
    same server `code` idempotently.
  Do **not** assert "Failed ⇒ unrecognized" — assert only that no tap ever yields a recognized chip
  whose code differs from what was provisioned.
- Debug "Инфо о чипе": tap a chip → confirm the model reads "NTAG213" (or actual stock); validates the
  FAST_READ-capable assumption.
- Admin «Проверка чипов КП» (`CheckChipScreen`): tap a freshly provisioned raw chip → reports
  «Привязан корректно» with the right КП number (confirms the read-only verify path reads the new raw
  format end-to-end, not just the scan path).
- **Expected regression:** fully close the app, tap a КП chip → app does **not** auto-launch anymore
  (NDEF cold-launch removed). The app must be open/foreground (or use the FAB) to mark a КП.
- Warm path: background the app, tap a КП chip → no longer foregrounds via NDEF (expected).

## Forward-looking note (not a task)
Participant chips (`type = 0x02`) are the format's reserved second type. A future effort will write
them and decide whether the participant `code` maps via the **server pool** (symmetric to КП: pool
delivers `code`/`bid` per participant, allowing the local uid-based `MemberChipBindingEntity` binding
to be deleted) or stays local. When that lands, `readChipCode`'s КП-only filter and `classifyTag`
gain an explicit type branch; until then a type=0x02 chip reads as `null` code and falls through to
the existing uid bracelet lookup.
