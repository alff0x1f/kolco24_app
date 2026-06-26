#!/usr/bin/env bash
#
# Pull the GPS track from a debug build on a USB-connected device and export it as GPX
# for quick verification (drag the .gpx into https://gpx.studio, Google Earth, or JOSM).
#
# Debug-only: relies on `run-as`, which needs a debuggable build.
#
# Usage:
#   ./scripts/dump-track.sh [out.gpx] [teamId]
#
# Examples:
#   ./scripts/dump-track.sh                 # all points -> track.gpx
#   ./scripts/dump-track.sh team7.gpx 7     # only teamId=7 -> team7.gpx
#
# Each recording session (one «Начать запись» tap) has its own segmentId; the GPX is split into one
# <trkseg> per segment so a stop->start gap is two polylines, never a teleport line across the map.
#
# Raw timestamp check (to compare trustedMs vs gpsTimeMs vs wallMs, plus elevation):
#   sqlite3 /tmp/kolco24-track.db \
#     'SELECT segmentId,lat,lon,accuracy,altitude,verticalAccuracyMeters,gpsTimeMs,trustedMs,wallMs,bootCount FROM track_points ORDER BY segmentId,elapsedRealtimeAt'
set -euo pipefail

PKG=ru.kolco24.kolco24
OUT=${1:-track.gpx}
TEAM=${2:-}
DB=/tmp/kolco24-track.db

# Find adb: PATH first, then the standard Android SDK location (it's often not on PATH).
ADB=$(command -v adb || true)
if [ -z "$ADB" ]; then
  for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$cand" ] && [ -x "$cand/platform-tools/adb" ] && { ADB="$cand/platform-tools/adb"; break; }
  done
fi
[ -n "$ADB" ]                 || { echo "adb not found (PATH or \$ANDROID_HOME/platform-tools)" >&2; exit 1; }
command -v sqlite3 >/dev/null || { echo "sqlite3 not found in PATH" >&2; exit 1; }

# Pick a device. Honor $ANDROID_SERIAL if set; else if more than one transport is online
# (e.g. the same phone over USB + wireless debugging) prefer the USB one (no _adb-tls suffix).
if [ -z "${ANDROID_SERIAL:-}" ]; then
  DEVICES=$("$ADB" devices | awk '$2=="device"{print $1}')
  COUNT=$(printf '%s\n' "$DEVICES" | grep -c .)
  if [ "$COUNT" -eq 0 ]; then
    echo "no device online (check: $ADB devices)" >&2; exit 1
  elif [ "$COUNT" -gt 1 ]; then
    ANDROID_SERIAL=$(printf '%s\n' "$DEVICES" | grep -v '_adb-tls' | head -1)
    [ -z "$ANDROID_SERIAL" ] && ANDROID_SERIAL=$(printf '%s\n' "$DEVICES" | head -1)
    echo "multiple transports; using $ANDROID_SERIAL (override with ANDROID_SERIAL=...)" >&2
  else
    ANDROID_SERIAL=$DEVICES
  fi
fi
export ANDROID_SERIAL   # adb honors this natively, no -s needed

# Pull the DB + WAL/SHM: recent rows may still live in the -wal file, not the main db.
for ext in "" "-wal" "-shm"; do
  "$ADB" exec-out run-as "$PKG" cat "databases/kolco24.db$ext" > "$DB$ext" 2>/dev/null || true
done
[ -s "$DB" ] || { echo "failed to pull databases/kolco24.db (debuggable build + device connected?)" >&2; exit 1; }

WHERE=""
[ -n "$TEAM" ] && WHERE="WHERE teamId = $TEAM"

# Emit GPX. time = trustedMs ?? gpsTimeMs ?? wallMs (epoch ms -> ISO8601 UTC).
# <ele> (meters) is emitted only when altitude is non-NULL — a NULL in the SQL
# concat would null the whole row and drop the point, so it's gated by CASE.
# accuracy (meters) is stuffed into <hdop> as a loose visual hint; viewers tolerate it.
# GPX schema order inside <trkpt> is <ele> then <time>, so ele comes first.
#
# Each row is prefixed with its segmentId + a TAB; awk opens a fresh <trkseg> whenever the
# segmentId changes, so each recording session is an independent polyline (no teleport line
# bridging a stop->start gap). Rows are grouped by segment and the segments are emitted in
# chronological order (by each segment's earliest point), points within a segment by capture time.
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<gpx version="1.1" creator="kolco24" xmlns="http://www.topografix.com/GPX/1/1"><trk>'
  sqlite3 "$DB" \
    "SELECT IFNULL(segmentId,'')||char(9)||
            '<trkpt lat=\"'||lat||'\" lon=\"'||lon||'\">'||
            CASE WHEN altitude IS NOT NULL THEN '<ele>'||altitude||'</ele>' ELSE '' END||
            '<time>'||strftime('%Y-%m-%dT%H:%M:%SZ', COALESCE(trustedMs,gpsTimeMs,wallMs)/1000, 'unixepoch')||'</time>'||
            '<hdop>'||accuracy||'</hdop></trkpt>'
     FROM track_points $WHERE
     ORDER BY min(elapsedRealtimeAt) OVER (PARTITION BY segmentId), segmentId, elapsedRealtimeAt ASC;" |
  awk -F'\t' '
    NR==1            { seg=$1; print "<trkseg>" }
    NR>1 && $1!=seg  { print "</trkseg>"; print "<trkseg>"; seg=$1 }
                     { print $2 }
    END              { if (NR>0) print "</trkseg>" }
  '
  echo '</trk></gpx>'
} > "$OUT"

COUNT=$(sqlite3 "$DB" "SELECT count(*) FROM track_points $WHERE;")
SEGMENTS=$(sqlite3 "$DB" "SELECT count(DISTINCT segmentId) FROM track_points $WHERE;")
echo "wrote $OUT ($COUNT points, $SEGMENTS segments); raw db at $DB"
