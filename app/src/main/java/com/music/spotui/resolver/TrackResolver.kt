package com.music.spotui.resolver

import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

data class TrackTarget(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isExplicitRemix: Boolean = false,
    val isExplicitLive: Boolean = false,
    val album: String = ""
)

data class Candidate(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val durationMs: Long,
    val isTopicChannel: Boolean,
    val isOfficialMusicVideo: Boolean,
    val explicit: Boolean? = null
)

/**
 * Multi-Tiered Track Matching & Fallback Engine
 *
 * Tier 1: YouTube Music "Song" Search (Score >= 80) -> Strict Studio Match
 * Tier 2: Official Music Video Search (Score >= 60) -> Official Video Match
 * Tier 3: Relaxed Broad Search (Score >= 40)        -> Broad Catalog Match
 * Tier 4: Permissive Fallback (Live/Remix)          -> Highest Non-Zero Scored Candidate
 */
class TrackResolver {

    companion object {
        private const val TAG = "TrackResolver"
    }

    /**
     * Resolves the best matching YouTube videoId for a given [TrackTarget].
     */
    suspend fun resolveTrack(target: TrackTarget): String? {
        val cleanTitle = sanitizeTitle(target.title)
        val cleanArtist = sanitizeArtist(target.artist)
        val baseQuery = "$cleanTitle $cleanArtist"

        // Tier 1: Strict YouTube Music Song Filter
        val tier1Items = runCatching {
            YouTube.search(baseQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        }.getOrDefault(emptyList())
        val tier1Candidates = tier1Items.map { it.toCandidate() }
        evaluateCandidates(target, tier1Candidates, strict = true, minScore = 80.0)?.let {
            Log.d(TAG, "Tier 1 (Song) matched: '${it.title}' [${it.videoId}]")
            return it.videoId
        }

        // Tier 2: YouTube Music Video Filter
        val tier2Items = runCatching {
            YouTube.search(baseQuery, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        }.getOrDefault(emptyList())
        val tier2Candidates = tier2Items.map { it.toCandidate() }
        evaluateCandidates(target, tier2Candidates, strict = false, allowVideos = true, minScore = 60.0)?.let {
            Log.d(TAG, "Tier 2 (Video) matched: '${it.title}' [${it.videoId}]")
            return it.videoId
        }

        // Tier 3: General YouTube Search (Relaxed text matching)
        val tier3Items = runCatching {
            YouTube.searchGeneral(baseQuery).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        }.getOrDefault(emptyList())
        val tier3Candidates = tier3Items.map { it.toCandidate() }
        evaluateCandidates(target, tier3Candidates, strict = false, allowVideos = true, minScore = 40.0)?.let {
            Log.d(TAG, "Tier 3 (Broad) matched: '${it.title}' [${it.videoId}]")
            return it.videoId
        }

        // Tier 4: Permissive Fallback (Accept Live / Remix / Cover as last resort)
        val allCandidates = (tier1Candidates + tier2Candidates + tier3Candidates).distinctBy { it.videoId }
        val fallback = allCandidates
            .map { it to calculatePermissiveScore(target, it) }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.first

        if (fallback != null) {
            Log.d(TAG, "Tier 4 (Permissive fallback) matched: '${fallback.title}' [${fallback.videoId}]")
            return fallback.videoId
        }

        Log.w(TAG, "No candidate resolved for target: $target")
        return null
    }

    /**
     * Deterministic ISRC Resolution via Deezer / MusicBrainz API.
     * Yields 100% exact studio match without acoustic or text ambiguity.
     */
    suspend fun resolveByIsrc(isrc: String): Pair<String, Long>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (isrc.isBlank()) return@withContext null
        try {
            val url = java.net.URL("https://api.deezer.com/track/isrc:$isrc")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "SpotUI/2.0")
            }
            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(jsonText)
                if (!json.has("error") && json.has("id")) {
                    val trackId = json.getLong("id").toString()
                    val durationSec = json.optLong("duration", 0L)
                    Log.d(TAG, "ISRC '$isrc' resolved deterministically to Deezer track ID $trackId ($durationSec s)")
                    return@withContext (trackId to durationSec * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ISRC resolution failed for $isrc: ${e.message}")
        }
        null
    }

    /**
     * Resolves all viable candidates ranked in order of best match to fallback options.
     */
    suspend fun resolveRankedCandidates(target: TrackTarget): List<Candidate> = coroutineScope {
        val cleanTitle = sanitizeTitle(target.title)
        val cleanArtist = sanitizeArtist(target.artist)
        val baseQuery = "$cleanTitle $cleanArtist"

        val t1Deferred = async {
            runCatching {
                YouTube.search(baseQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
            }.getOrDefault(emptyList()).map { it.toCandidate() }
        }

        val t2Deferred = async {
            runCatching {
                YouTube.search(baseQuery, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
            }.getOrDefault(emptyList()).map { it.toCandidate() }
        }

        val t3Deferred = async {
            runCatching {
                YouTube.searchGeneral(baseQuery).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
            }.getOrDefault(emptyList()).map { it.toCandidate() }
        }

        val t1Items = t1Deferred.await()
        val t2Items = t2Deferred.await()
        val t3Items = t3Deferred.await()

        val combined = (t1Items + t2Items + t3Items).distinctBy { it.videoId }
        if (combined.isEmpty()) return@coroutineScope emptyList()

        combined.mapNotNull { candidate ->
            val score = calculateScore(target, candidate, strict = false, allowVideos = true)
            if (score > 0.0) candidate to score else {
                val pScore = calculatePermissiveScore(target, candidate)
                if (pScore > 0.0) candidate to pScore else null
            }
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun evaluateCandidates(
        target: TrackTarget,
        candidates: List<Candidate>,
        strict: Boolean,
        allowVideos: Boolean = false,
        minScore: Double = if (strict) 80.0 else 55.0
    ): Candidate? {
        var bestCandidate: Candidate? = null
        var highestScore = minScore

        for (candidate in candidates) {
            val score = calculateScore(target, candidate, strict, allowVideos)
            if (score > highestScore) {
                highestScore = score
                bestCandidate = candidate
            }
        }
        return bestCandidate
    }

    fun calculateScore(
        target: TrackTarget,
        candidate: Candidate,
        strict: Boolean,
        allowVideos: Boolean
    ): Double {
        val hasDuration = target.durationMs > 0 && candidate.durationMs > 0
        val durationDiff = if (hasDuration) abs(target.durationMs - candidate.durationMs) else 0L

        // Strict duration gate
        if (hasDuration) {
            if (strict && durationDiff > 3500) return 0.0
            if (!strict && durationDiff > 25000 && !allowVideos) return 0.0
        }

        val titleSim = StringSimilarity.levenshteinRatio(
            sanitizeTitle(target.title),
            sanitizeTitle(candidate.title)
        )
        val artistSim = StringSimilarity.tokenOverlap(
            sanitizeArtist(target.artist),
            candidate.channelTitle
        )

        // If title similarity is too low (< 0.55), this is an unrelated track (e.g. different song by same artist). Reject!
        if (titleSim < 0.55) return 0.0

        var score = (titleSim * 50.0) + (artistSim * 25.0)

        if (candidate.isTopicChannel && titleSim >= 0.70) score += 30.0
        if (candidate.isOfficialMusicVideo && titleSim >= 0.70) score += 20.0
        if (artistSim >= 0.8 && titleSim >= 0.70) score += 25.0
        if (titleSim >= 0.85) score += 25.0

        // Negative keyword noise filtering
        val noiseWords = listOf(
            "live", "remix", "rmx", "cover", "acoustic", "slowed", "reverb",
            "tribute", "karaoke", "instrumental", "8d", "sped up", "speed up",
            "club mix", "vip mix", "dub mix", "mashup", "parody"
        )
        val candidateTitleLower = candidate.title.lowercase()
        val targetTitleLower = target.title.lowercase()

        for (word in noiseWords) {
            val targetHasWord = targetTitleLower.contains(word)
            val candidateHasWord = candidateTitleLower.contains(word)
            if (!targetHasWord && candidateHasWord) {
                if (strict) return 0.0 // Reject completely in strict mode
                score -= 80.0
            }
        }

        // Apply duration penalty in seconds, capped so official videos with intros are not destroyed
        if (hasDuration) {
            val durationDiffSec = durationDiff / 1000.0
            val durationPenalty = (durationDiffSec * 1.5).coerceAtMost(25.0)
            score -= durationPenalty
        }

        return score.coerceAtLeast(0.0)
    }

    fun calculatePermissiveScore(target: TrackTarget, candidate: Candidate): Double {
        val titleSim = StringSimilarity.levenshteinRatio(sanitizeTitle(target.title).lowercase(), sanitizeTitle(candidate.title).lowercase())
        if (titleSim < 0.50) return 0.0
        val noiseWords = listOf("live", "remix", "rmx", "cover", "acoustic", "slowed", "reverb", "tribute", "karaoke", "instrumental")
        val hasNoise = noiseWords.any { noise ->
            !target.title.contains(noise, ignoreCase = true) && candidate.title.contains(noise, ignoreCase = true)
        }
        if (hasNoise) return 0.0
        val hasDuration = target.durationMs > 0 && candidate.durationMs > 0
        val durationPenalty = if (hasDuration) abs(target.durationMs - candidate.durationMs) / 1000.0 else 0.0
        return (titleSim * 70.0 - durationPenalty).coerceAtLeast(0.0)
    }

    fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(.*?(?:remaster|deluxe|edition|anniversary|version).*?\)"""), "")
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""(?i)\s*(?:feat\.?|ft\.?|with)\s+.*"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:official\s*(?:music\s*)?video|audio|lyrics?|visualizer).*"""), "")
            .trim()
    }

    fun sanitizeArtist(artist: String): String {
        return artist.split(Regex("[,/&]|(?i)\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+with\\s+")).firstOrNull()?.trim().orEmpty()
            .ifBlank { artist.trim() }
    }

    private fun SongItem.toCandidate(): Candidate {
        val channelName = artists.firstOrNull()?.name.orEmpty()
        val isTopic = channelName.contains("Topic", ignoreCase = true) ||
            title.contains("Topic", ignoreCase = true) ||
            artists.any { it.name.contains("Topic", ignoreCase = true) }
        val isOmv = musicVideoType?.contains("OMV", ignoreCase = true) == true ||
            channelName.contains("VEVO", ignoreCase = true) ||
            title.contains("Official Music Video", ignoreCase = true) ||
            title.contains("Official Video", ignoreCase = true) ||
            title.contains("Official Audio", ignoreCase = true) ||
            title.contains("Audio", ignoreCase = true)

        return Candidate(
            videoId = id,
            title = title,
            channelTitle = channelName,
            durationMs = (duration ?: 0) * 1000L,
            isTopicChannel = isTopic,
            isOfficialMusicVideo = isOmv,
            explicit = explicit
        )
    }
}
