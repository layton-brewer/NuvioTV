package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.player.StreamSmartRanker
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Upper bound on silent source swaps for one title, so a dead network cannot loop forever. */
internal const val MAX_SOURCE_FAILOVER_ATTEMPTS = 6
private const val SOURCE_FAILOVER_POLL_COUNT = 30
private const val SOURCE_FAILOVER_POLL_INTERVAL_MS = 500L

/** Identity of a source for "already tried" bookkeeping. */
internal fun Stream.failoverKey(): String =
    getStreamUrl()?.takeIf { it.isNotBlank() }
        ?: getEffectiveInfoHash()?.let { "torrent:${it.lowercase()}:${getEffectiveFileIdx()}" }
        ?: stableKey()

/**
 * The sources worth trying after the playing one failed, best first: everything auto-play
 * could play, ranked for this device, minus what was already tried.
 */
internal fun rankSourceFailoverCandidates(
    streams: List<Stream>,
    triedKeys: Set<String>,
    isSeries: Boolean
): List<Stream> =
    StreamSmartRanker.rank(
        streams.filter { StreamAutoPlaySelector.isPlayable(it) && it.failoverKey() !in triedKeys },
        isSeries = isSeries
    )

/**
 * Replaces a source that failed to play with the next best source for this title and carries
 * on from the saved position. It runs behind the loading overlay: the viewer only sees an
 * error once every backup has failed too. Active whenever auto-play is on.
 *
 * Returns `true` when a replacement is being tried and the error must not be shown yet.
 */
internal fun PlayerRuntimeController.tryFailoverToNextSource(errorMessage: String): Boolean {
    if (streamAutoPlayModeSetting == StreamAutoPlayMode.MANUAL) return false

    val contentKey = "$currentVideoId|$currentSeason|$currentEpisode"
    if (sourceFailoverContentKey != contentKey) {
        sourceFailoverContentKey = contentKey
        sourceFailoverTriedKeys.clear()
        sourceFailoverAttempts = 0
    }
    if (sourceFailoverAttempts >= MAX_SOURCE_FAILOVER_ATTEMPTS) return false
    sourceFailoverAttempts++

    val failedUrl = currentStreamUrl
    if (failedUrl.isNotBlank()) sourceFailoverTriedKeys += failedUrl
    val failedInfoHash = _uiState.value.currentStreamInfoHash ?: navigationArgs.infoHash
    val season = currentSeason
    val episode = currentEpisode
    Log.i(PlayerRuntimeController.TAG, "Source failed to play, trying the next best source")

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        streamCacheKey?.let { streamLinkCacheDataStore.remove(it) }
        // Normally answered from the search that auto-play already ran for this title.
        loadSourceStreams(forceRefresh = false)
        var polls = 0
        while (polls < SOURCE_FAILOVER_POLL_COUNT) {
            val sources = _uiState.value
            if (!sources.isLoadingSourceStreams && (sources.sourceAllStreams.isNotEmpty() || polls > 2)) break
            delay(SOURCE_FAILOVER_POLL_INTERVAL_MS)
            polls++
        }

        val p2pEnabled = runCatching { torrentSettings.settings.first().p2pEnabled }.getOrDefault(false)
        val candidates = rankSourceFailoverCandidates(
            streams = _uiState.value.sourceAllStreams.filterNot { stream ->
                failedInfoHash != null && stream.getStreamUrl().isNullOrBlank() &&
                    stream.getEffectiveInfoHash().equals(failedInfoHash, ignoreCase = true)
            },
            triedKeys = sourceFailoverTriedKeys,
            isSeries = season != null && episode != null
        )

        for (candidate in candidates) {
            sourceFailoverTriedKeys += candidate.failoverKey()
            val playable: Stream = when {
                candidate.serverTarget != null && candidate.getStreamUrl().isNullOrBlank() ->
                    prepareServerStream(candidate)
                candidate.youTubeIdToResolve() != null -> resolveYouTubeStream(candidate)
                candidate.isTorrent() || candidate.isDirectDebrid() ->
                    resolveDirectDebridStreamIfNeeded(candidate, season, episode)
                else -> candidate
            } ?: continue
            val url = playable.getStreamUrl()
            when {
                !url.isNullOrBlank() -> {
                    if (url == failedUrl || url in sourceFailoverTriedKeys && url != candidate.getStreamUrl()) continue
                    sourceFailoverTriedKeys += url
                }
                // No link: only a peer-to-peer torrent is left, which needs the viewer's consent.
                !(playable.isTorrent() && p2pEnabled) -> continue
            }
            Log.i(PlayerRuntimeController.TAG, "Switching to backup source from ${playable.addonName}")
            // Resumes from the saved position and resets the per-source retry counters.
            switchToSourceStream(playable)
            return@launch
        }

        Log.w(PlayerRuntimeController.TAG, "No backup source could be played")
        _uiState.update {
            it.copy(
                error = errorMessage,
                isBuffering = false,
                showLoadingOverlay = false,
                showPauseOverlay = false,
                isLoadingSourceStreams = false
            )
        }
    }
    return true
}
