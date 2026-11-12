package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/**
 * Ranks streams for hands-off playback ("Smart" auto-play mode).
 *
 * Order of importance:
 * 1. Availability: links that play instantly (direct URLs, cached debrid, media servers) come
 *    before anything that still has to download.
 * 2. Junk releases (cam / telesync / screener) are pushed to the bottom.
 * 3. Series only: full-season packs first, so every episode of a season comes from the same
 *    release and the next episode starts without a new search miss.
 * 4. Resolution fit for this device: the device's own resolution first, then higher
 *    resolutions (they still look right, just cost more bandwidth), then lower ones.
 * 5. Source quality (remux > bluray > web-dl > webrip > hdtv).
 * 6. Original order, which is the user's add-on order.
 */
object StreamSmartRanker {

    /** Height in pixels of the best picture this device can show. Set by the platform at startup. */
    @Volatile
    var deviceMaxVideoHeight: Int = DEFAULT_MAX_VIDEO_HEIGHT

    const val DEFAULT_MAX_VIDEO_HEIGHT = 1080

    fun rank(
        streams: List<Stream>,
        isSeries: Boolean,
        maxVideoHeight: Int = deviceMaxVideoHeight,
    ): List<Stream> {
        if (streams.size < 2) return streams
        val targetHeight = normalizeHeight(maxVideoHeight)
        return streams
            .mapIndexed { index, stream -> index to score(stream, isSeries, targetHeight) }
            .sortedWith(
                compareBy<Pair<Int, Score>> { it.second.availabilityTier }
                    .thenBy { if (it.second.isJunk) 1 else 0 }
                    .thenBy { if (it.second.isSeasonPack) 0 else 1 }
                    .thenByDescending { it.second.resolutionFit }
                    .thenByDescending { it.second.sourceQuality }
                    .thenBy { it.first },
            )
            .map { streams[it.first] }
    }

    internal data class Score(
        val availabilityTier: Int,
        val isJunk: Boolean,
        val isSeasonPack: Boolean,
        val resolutionFit: Int,
        val sourceQuality: Int,
    )

    internal fun score(stream: Stream, isSeries: Boolean, targetHeight: Int): Score {
        val text = stream.searchableText()
        return Score(
            availabilityTier = availabilityTier(stream, text),
            isJunk = JUNK_REGEX.containsMatchIn(text),
            isSeasonPack = isSeries && isSeasonPack(stream, text),
            resolutionFit = resolutionFit(parseHeight(stream, text), targetHeight),
            sourceQuality = sourceQuality(text),
        )
    }

    private fun Stream.searchableText(): String = buildString {
        fun add(value: String?) {
            if (!value.isNullOrBlank()) append(value).append('\n')
        }
        add(name)
        add(title)
        add(description)
        add(behaviorHints?.filename)
        add(clientResolve?.torrentName)
        add(clientResolve?.filename)
        add(clientResolve?.stream?.raw?.torrentName)
        add(clientResolve?.stream?.raw?.filename)
    }

    /** 0 = plays instantly, 1 = probably fine, 2 = has to download first. */
    internal fun availabilityTier(stream: Stream, text: String): Int {
        if (UNCACHED_REGEX.containsMatchIn(text)) return 2
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) return 2
        if (stream.serverTarget != null) return 0
        if (stream.isDirectDebrid()) return 0
        if (stream.isTorrent() && stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) return 0
        if (stream.getStreamUrl() != null) return 0
        if (stream.isTorrent()) return 2
        return 1
    }

    internal fun isSeasonPack(stream: Stream, text: String): Boolean {
        val raw = stream.clientResolve?.stream?.raw
        val parsed = raw?.parsed
        if (parsed != null && !parsed.seasons.isNullOrEmpty() && parsed.episodes.orEmpty().size != 1) return true
        val size = raw?.size
        val folderSize = raw?.folderSize
        if (size != null && folderSize != null && size > 0 && folderSize > size * 2) return true
        if (PACK_MARKER_REGEX.containsMatchIn(text)) return true
        // Add-ons such as Torrentio print the torrent name on one line and the episode file on
        // the next. A line naming a season without an episode is the pack's own name.
        return text.lineSequence().any { line ->
            SEASON_ONLY_REGEX.containsMatchIn(line) && !EPISODE_REGEX.containsMatchIn(line)
        }
    }

    internal fun parseHeight(stream: Stream, text: String): Int? =
        stream.clientResolve?.stream?.raw?.parsed?.resolution?.let(::parseHeightText)
            ?: parseHeightText(text)

    internal fun parseHeightText(text: String): Int? = when {
        RES_2160.containsMatchIn(text) -> 2160
        RES_1440.containsMatchIn(text) -> 1440
        RES_1080.containsMatchIn(text) -> 1080
        RES_720.containsMatchIn(text) -> 720
        RES_576.containsMatchIn(text) -> 576
        RES_480.containsMatchIn(text) -> 480
        RES_360.containsMatchIn(text) -> 360
        else -> null
    }

    /** Snaps a raw display height to the nearest standard video height at or below it. */
    internal fun normalizeHeight(height: Int): Int = when {
        height >= 2160 -> 2160
        height >= 1440 -> 1440
        height >= 1080 -> 1080
        height >= 720 -> 720
        height > 0 -> 480
        else -> DEFAULT_MAX_VIDEO_HEIGHT
    }

    /** Higher is better. The device's own resolution wins, then anything above it, then below. */
    internal fun resolutionFit(height: Int?, targetHeight: Int): Int = when {
        height == null -> UNKNOWN_RESOLUTION_FIT
        height == targetHeight -> 10_000
        // Above the screen: still looks right. Prefer the smallest one that covers the screen.
        height > targetHeight -> 9_000 - (height - targetHeight) / 10
        else -> height
    }

    internal fun sourceQuality(text: String): Int = when {
        REMUX_REGEX.containsMatchIn(text) -> 5
        BLURAY_REGEX.containsMatchIn(text) -> 4
        WEBDL_REGEX.containsMatchIn(text) -> 3
        WEBRIP_REGEX.containsMatchIn(text) -> 2
        HDTV_REGEX.containsMatchIn(text) -> 1
        else -> 2
    }

    // Unknown resolution sits between 720p and 480p.
    private const val UNKNOWN_RESOLUTION_FIT = 600

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)

    private val RES_2160 = Regex("""\b(2160p?|4k|uhd)\b""", OPTIONS)
    private val RES_1440 = Regex("""\b(1440p|2k|qhd)\b""", OPTIONS)
    private val RES_1080 = Regex("""\b(1080[pi]?|fhd|full\s?hd)\b""", OPTIONS)
    private val RES_720 = Regex("""\b720p?\b""", OPTIONS)
    private val RES_576 = Regex("""\b576p?\b""", OPTIONS)
    private val RES_480 = Regex("""\b(480p?|sd)\b""", OPTIONS)
    private val RES_360 = Regex("""\b360p\b""", OPTIONS)

    private val JUNK_REGEX = Regex(
        """\b(cam|camrip|hdcam|hd-cam|telesync|hdts|hd-ts|telecine|hdtc|screener|dvdscr|scr)\b|\bTS\b(?!\s*$)""",
        OPTIONS,
    )
    private val REMUX_REGEX = Regex("""\bremux\b""", OPTIONS)
    private val BLURAY_REGEX = Regex("""\b(blu-?ray|bdrip|brrip|bd)\b""", OPTIONS)
    private val WEBDL_REGEX = Regex("""\bweb[-. ]?dl\b|\bweb\b""", OPTIONS)
    private val WEBRIP_REGEX = Regex("""\bweb[-. ]?rip\b""", OPTIONS)
    private val HDTV_REGEX = Regex("""\b(hdtv|pdtv|dvdrip|hdrip)\b""", OPTIONS)

    private val UNCACHED_REGEX = Regex(
        """\[[a-z]{2,3}\s*(download|⬇️?)\]|⏳|⬇️|\buncached\b|\bnot\s+cached\b|\bdownload\s+to\b""",
        OPTIONS,
    )

    private val PACK_MARKER_REGEX = Regex(
        """📦|\b(complete|season\s?pack|full\s?season|s\d{1,2}\s?-\s?s?\d{1,2}|seasons?\s?\d{1,2}\s?(-|to)\s?\d{1,2})\b""",
        OPTIONS,
    )
    private val SEASON_ONLY_REGEX = Regex("""\bS\d{1,2}(?![\dEex])\b|\bseason[ ._-]?\d{1,2}\b""", OPTIONS)
    private val EPISODE_REGEX = Regex(
        """S\d{1,2}[ ._-]?E\d{1,3}|\b\d{1,2}x\d{2,3}\b|\bE\d{2,3}\b|\bepisode[ ._-]?\d{1,3}\b""",
        OPTIONS,
    )
}
