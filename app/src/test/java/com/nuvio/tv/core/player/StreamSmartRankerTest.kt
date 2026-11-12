package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class StreamSmartRankerTest {

    private fun stream(
        name: String,
        description: String? = null,
        url: String? = "https://example.com/${name.hashCode()}.mkv",
        infoHash: String? = null
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )

    private fun names(streams: List<Stream>) = streams.map { it.name }

    @Test
    fun `movie on a 1080p device prefers 1080p then 4k then lower`() {
        val ranked = StreamSmartRanker.rank(
            streams = listOf(
                stream("720p WEB-DL"),
                stream("4K HDR"),
                stream("480p"),
                stream("1080p BluRay"),
            ),
            isSeries = false,
            maxVideoHeight = 1080,
        )
        assertEquals(listOf("1080p BluRay", "4K HDR", "720p WEB-DL", "480p"), names(ranked))
    }

    @Test
    fun `movie on a 4k tv prefers 2160p`() {
        val ranked = StreamSmartRanker.rank(
            streams = listOf(stream("1080p"), stream("Movie 2160p REMUX"), stream("720p")),
            isSeries = false,
            maxVideoHeight = 2160,
        )
        assertEquals("Movie 2160p REMUX", ranked.first().name)
    }

    @Test
    fun `instant links beat torrents that still have to download`() {
        val ranked = StreamSmartRanker.rank(
            streams = listOf(
                stream("4K torrent", url = null, infoHash = "a".repeat(40)),
                stream("[RD download] 1080p"),
                stream("[RD+] 720p"),
            ),
            isSeries = false,
            maxVideoHeight = 2160,
        )
        assertEquals("[RD+] 720p", ranked.first().name)
    }

    @Test
    fun `cam releases go last even at the right resolution`() {
        val ranked = StreamSmartRanker.rank(
            streams = listOf(stream("1080p HDCAM"), stream("720p WEB-DL")),
            isSeries = false,
            maxVideoHeight = 1080,
        )
        assertEquals(listOf("720p WEB-DL", "1080p HDCAM"), names(ranked))
    }

    @Test
    fun `series prefer season packs over single episodes`() {
        val single = stream("1080p", description = "Show.S01E03.1080p.WEB-DL.mkv")
        val pack = stream("720p", description = "Show.S01.720p.BluRay\nShow.S01E03.720p.BluRay.mkv")
        val ranked = StreamSmartRanker.rank(listOf(single, pack), isSeries = true, maxVideoHeight = 1080)
        assertEquals(pack, ranked.first())
        // The same list for a movie ignores packs and goes by resolution.
        assertEquals(single, StreamSmartRanker.rank(listOf(single, pack), isSeries = false, maxVideoHeight = 1080).first())
    }

    @Test
    fun `season pack detection`() {
        fun isPack(text: String) = StreamSmartRanker.isSeasonPack(stream("x"), text)
        assertTrue(isPack("Show.S02.1080p.WEB-DL"))
        assertTrue(isPack("Show Season 2 Complete 1080p"))
        assertTrue(isPack("Show S01-S05 BluRay"))
        assertFalse(isPack("Show.S02E05.1080p.WEB-DL.mkv"))
        assertFalse(isPack("Show 2x05 720p"))
    }

    @Test
    fun `resolution parsing`() {
        assertEquals(2160, StreamSmartRanker.parseHeightText("Movie.2160p.UHD"))
        assertEquals(2160, StreamSmartRanker.parseHeightText("Torrentio\n4k DV"))
        assertEquals(1080, StreamSmartRanker.parseHeightText("Movie 1080p x265"))
        assertEquals(720, StreamSmartRanker.parseHeightText("720p"))
        assertNull(StreamSmartRanker.parseHeightText("Movie x264"))
    }

    @Test
    fun `order is stable when nothing distinguishes the streams`() {
        val streams = listOf(stream("A"), stream("B"), stream("C"))
        assertEquals(streams, StreamSmartRanker.rank(streams, isSeries = false, maxVideoHeight = 1080))
    }

    @Test
    fun `backup ranking skips sources that were already tried`() {
        val a = stream("1080p A")
        val b = stream("720p B")
        val ranked = com.nuvio.tv.ui.screens.player.rankSourceFailoverCandidates(
            streams = listOf(a, b),
            triedKeys = setOf(a.url!!),
            isSeries = false
        )
        assertEquals(listOf(b), ranked)
    }

    @Test
    fun `smart mode backups follow the first pick`() {
        val low = stream("480p")
        val best = stream("1080p WEB-DL")
        val ranked = StreamAutoPlaySelector.rankAutoPlayStreams(
            streams = listOf(low, best),
            mode = com.nuvio.tv.data.local.StreamAutoPlayMode.SMART,
            regexPattern = "",
            source = com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )
        assertEquals(listOf(best, low), ranked)
    }
}
