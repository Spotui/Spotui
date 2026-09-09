package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Utility object for creating search queries from Spotify track data.
 * Highly optimized for precise YouTube / YouTube Music audio matching.
 */
object SpotifyMapper {

    private val FEAT_PATTERN = Regex("""\s*[\(\[]\s*(feat|ft|with)\.?\s+.*?[\]\)]""", RegexOption.IGNORE_CASE)
    private val BRACKET_PATTERN = Regex("""\[.*?\]""")
    private val REMASTER_PATTERN = Regex("""\s*[-–—]?\s*[\(\[]?\s*(\d{4}\s+)?remaster(ed)?(\s+\d{4})?\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    private val RADIO_EDIT_PATTERN = Regex("""\s*[-–—]?\s*[\(\[]?\s*(radio\s+edit|single\s+version|original\s+mix|album\s+version)\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    private val LIVE_PATTERN = Regex("""\s*[-–—]?\s*[\(\[]?\s*live(\s+at|\s+from|\s+in)?.*?[\]\)]?""", RegexOption.IGNORE_CASE)
    private val AUDIO_TAG_PATTERN = Regex("""\s*[\(\[]\s*(official\s+audio|official\s+video|official\s+music\s+video|lyric\s+video|audio|lyrics?)\s*[\]\)]""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_PATTERN = Regex("""[^a-z0-9\s]""")
    private val MULTI_SPACE_PATTERN = Regex("""\s+""")

    private const val NORM_CACHE_MAX_SIZE = 256
    private const val EARLY_EXIT_THRESHOLD = 0.95

    private val normalizeCache = object : LinkedHashMap<String, String>(
        NORM_CACHE_MAX_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > NORM_CACHE_MAX_SIZE
    }

    private val bigramCache = object : LinkedHashMap<String, Set<String>>(
        NORM_CACHE_MAX_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean =
            size > NORM_CACHE_MAX_SIZE
    }

    data class PrecomputedTrack(
        val normalizedTitle: String,
        val titleBigrams: Set<String>,
        val normalizedArtist: String,
        val artistBigrams: Set<String>,
        val durationMs: Int,
    )

    /**
     * Clean track title for query building, stripping noise that causes YouTube search mismatches.
     */
    fun cleanTitleForSearch(title: String): String {
        return stripDiacritics(title)
            .replace(FEAT_PATTERN, "")
            .replace(REMASTER_PATTERN, "")
            .replace(RADIO_EDIT_PATTERN, "")
            .replace(AUDIO_TAG_PATTERN, "")
            .replace(Regex("""\s*[-–—]?\s*[\(\[]?\s*(deluxe|anniversary|bonus track|soundtrack|ost)\s*[\)\]]?""", RegexOption.IGNORE_CASE), "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()
    }

    private fun stripDiacritics(str: String): String {
        val nfd = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
    }

    /**
     * Builds prioritized YouTube search queries for a track.
     */
    fun buildSearchQueries(title: String, artist: String): List<String> {
        val cleanTitle = cleanTitleForSearch(title)
        val cleanArtist = stripDiacritics(artist.replace(FEAT_PATTERN, "").trim())
        val primaryArtist = cleanArtist.split(Regex("""(?i)\s*(?:,|\band\b|&|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*"""))
            .firstOrNull { it.isNotBlank() }?.trim() ?: cleanArtist

        val queries = mutableListOf<String>()

        if (primaryArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            queries.add("$cleanTitle $primaryArtist")
            queries.add("$primaryArtist $cleanTitle")
            queries.add("$cleanTitle $primaryArtist official audio")
            queries.add("$cleanTitle $primaryArtist topic")
            if (cleanArtist != primaryArtist) {
                queries.add("$cleanTitle $cleanArtist")
                queries.add("$cleanArtist $cleanTitle")
            }
        } else if (cleanTitle.isNotBlank()) {
            queries.add(cleanTitle)
        } else if (artist.isNotBlank()) {
            queries.add("$artist $title")
        }
        return queries.distinct()
    }

    fun buildSearchQuery(track: SpotifyTrack): String {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val title = track.name
        val cleanTitle = cleanTitleForSearch(title)
        return if (artist.isEmpty()) cleanTitle else "$artist $cleanTitle"
    }

    fun getPlaylistThumbnail(playlist: SpotifyPlaylist): String? {
        return playlist.images.let { images ->
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    fun getTrackThumbnail(track: SpotifyTrack): String? {
        return track.album?.images?.let { images ->
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    fun precompute(
        title: String,
        artist: String,
        durationMs: Int,
    ): PrecomputedTrack {
        val normTitle = cachedNormalize(title)
        val normArtist = cachedNormalize(artist)
        return PrecomputedTrack(
            normalizedTitle = normTitle,
            titleBigrams = cachedBigrams(normTitle),
            normalizedArtist = normArtist,
            artistBigrams = cachedBigrams(normArtist),
            durationMs = durationMs,
        )
    }

    fun matchScore(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normSpotifyTitle = cachedNormalize(spotifyTitle)
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normSpotifyArtist = cachedNormalize(spotifyArtist)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore = bigramSimilarity(
            normSpotifyTitle, cachedBigrams(normSpotifyTitle),
            normCandidateTitle, cachedBigrams(normCandidateTitle),
        )
        val artistScore = bigramSimilarity(
            normSpotifyArtist, cachedBigrams(normSpotifyArtist),
            normCandidateArtist, cachedBigrams(normCandidateArtist),
        )

        val durationScore = durationScore(spotifyDurationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    fun matchScorePrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore = bigramSimilarity(
            precomputed.normalizedTitle, precomputed.titleBigrams,
            normCandidateTitle, cachedBigrams(normCandidateTitle),
        )
        val artistScore = bigramSimilarity(
            precomputed.normalizedArtist, precomputed.artistBigrams,
            normCandidateArtist, cachedBigrams(normCandidateArtist),
        )

        val durationScore = durationScore(precomputed.durationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    fun earlyExitThreshold(): Double = EARLY_EXIT_THRESHOLD

    private fun durationScore(spotifyDurationMs: Int, candidateDurationSec: Int?): Double {
        if (candidateDurationSec == null || spotifyDurationMs <= 0) return 0.5
        val diff = kotlin.math.abs(spotifyDurationMs / 1000 - candidateDurationSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 4 -> 0.85
            diff <= 8 -> 0.6
            diff <= 20 -> 0.25
            else -> 0.0
        }
    }

    private fun cachedNormalize(title: String): String {
        normalizeCache[title]?.let { return it }
        val normalized = normalizeTitle(title)
        normalizeCache[title] = normalized
        return normalized
    }

    private fun cachedBigrams(normalized: String): Set<String> {
        bigramCache[normalized]?.let { return it }
        val bigrams = if (normalized.length < 2) emptySet() else normalized.windowed(2).toSet()
        bigramCache[normalized] = bigrams
        return bigrams
    }

    private fun normalizeTitle(title: String): String {
        return stripDiacritics(title).lowercase()
            .replace(FEAT_PATTERN, "")
            .replace(BRACKET_PATTERN, "")
            .replace(REMASTER_PATTERN, "")
            .replace(RADIO_EDIT_PATTERN, "")
            .replace(AUDIO_TAG_PATTERN, "")
            .replace(NON_ALNUM_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()
    }

    private fun bigramSimilarity(
        a: String, bigramsA: Set<String>,
        b: String, bigramsB: Set<String>,
    ): Double {
        if (a == b) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val intersection = bigramsA.count { it in bigramsB }
        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}
