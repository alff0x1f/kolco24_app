package ru.kolco24.kolco24.data.map

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves a race's `map_url` against [baseUrl], the API base URL of the origin that served it.
 *
 * - `null`/blank → `null` (no map).
 * - A root-relative path (`/media/maps/8.mbtiles`) → that path on [baseUrl]'s host (the base's own
 *   path is dropped). In LAN mode this points at the race server.
 * - `//host…`, or a path with a backslash / whitespace / control char → `null`: URL parsers treat `\`
 *   as `/` and strip tab/newline, so such a value could point the download at another host.
 * - Anything else (an absolute `https://…` URL) is returned as is.
 */
fun resolveMapUrl(mapUrl: String?, baseUrl: String): String? {
    if (mapUrl.isNullOrBlank()) return null
    if (!mapUrl.startsWith("/")) return mapUrl
    if (mapUrl.startsWith("//") || mapUrl.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) {
        return null
    }
    return baseUrl.toHttpUrlOrNull()?.resolve(mapUrl)?.toString()
}
