package com.music.spotui.soulseek

import android.content.Context
import android.util.Log
import com.music.spotui.data.preferences.resetSoulseekCredentials
import com.music.spotui.data.preferences.soulseekCredentials
import com.music.spotui.lossless.LosslessSource
import java.io.File

/**
 * Last-resort lossless source: finds the track as FLAC on Soulseek (P2P), fetches it
 * into a cache and hands back the file. Peers are slow or queued at times, so the
 * player only uses this as a background upgrade while another stream plays.
 */
object SoulseekSource {
    private const val TAG = "SoulseekSource"
    private const val CACHE_LIMIT_BYTES = 1L shl 30 // 1 GB of cached FLACs

    /** Already-fetched FLAC for the track, or null. Cheap: no network. */
    fun cached(context: Context, cacheKey: String): File? =
        File(File(context.cacheDir, "slsk"), "$cacheKey.flac").takeIf { it.length() > 0 }

    /** Cached or freshly fetched FLAC for the track, or null. Blocking: IO only. */
    fun flac(context: Context, cacheKey: String, title: String, artist: String, durationSec: Int): File? {
        val dir = File(context.cacheDir, "slsk").apply { mkdirs() }
        val file = File(dir, "$cacheKey.flac")
        if (file.length() > 0) {
            // Reuse a cached file only if it is this track: the key can collide (it falls
            // back to a string hash), and a wrong file would play under this track's title.
            if (isRightTrack(file, durationSec)) return file.also { it.setLastModified(System.currentTimeMillis()) }
            Log.d(TAG, "cached $cacheKey.flac is not this track: refetching")
            file.delete()
        }
        if (!login(context)) return null
        val query = "${artist.substringBefore(",")} ${baseTitle(title)}".replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        // Peers answer by connecting to us; from behind NAT that takes a while.
        val results = SoulseekClient.search(query, windowMs = 10_000)
        val candidates = ranked(results, title, artist, durationSec)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no matching FLAC for \"$query\" (${results.size} results, ${results.count { it.isFlac }} FLAC)")
            return null
        }
        // Peers often refuse (queue full, they don't upload to accounts sharing nothing),
        // so try the next ones instead of giving up on the first "no".
        val part = File(dir, "$cacheKey.flacpart")
        val deadline = System.currentTimeMillis() + 150_000
        for (c in candidates.distinctBy { it.username }.take(4)) {
            val left = deadline - System.currentTimeMillis()
            if (left < 10_000) break
            val fetched = SoulseekClient.download(c, part, timeoutMs = minOf(90_000, left))
            when {
                !fetched -> Log.d(TAG, "fetch from ${c.username} failed: ${SoulseekClient.lastError ?: "timed out"}")
                !isRightTrack(part, durationSec) -> Log.d(TAG, "${c.username}: not the expected FLAC, skipped")
                part.renameTo(file) -> {
                    Log.d(TAG, "FLAC from ${c.username}: ${c.displayName}")
                    trimCache(dir)
                    return file
                }
            }
            part.delete()
        }
        return null
    }

    private fun login(context: Context): Boolean {
        val (user, pass) = soulseekCredentials(context)
        if (SoulseekClient.ensureLoggedIn(user, pass)) return true
        // Name taken by someone else (or rejected): make a fresh account once.
        if (SoulseekClient.lastError?.contains("INVALID", ignoreCase = true) == true) {
            resetSoulseekCredentials(context)
            val (u2, p2) = soulseekCredentials(context)
            return SoulseekClient.ensureLoggedIn(u2, p2)
        }
        return false
    }

    // Spotify titles carry extras files rarely have: "(feat. X & Y)", "[Radio Edit]",
    // " - 2013 Remaster". Searching for every word of those finds nothing.
    internal fun baseTitle(title: String): String =
        title.substringBefore(" - ").replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { title }

    private val otherVersion = Regex("""\b(live|remix|instrumental|karaoke|acoustic|cover|sped|slowed|nightcore)\b""")

    /**
     * Same song only: a FLAC whose file name holds the title and whose path holds the
     * artist, no live/remix/... unless the title says so, and a length within 5 s when
     * both are known. Best peer among those.
     */
    internal fun pick(files: List<SlskFile>, title: String, artist: String, durationSec: Int): SlskFile? =
        ranked(files, title, artist, durationSec).firstOrNull()

    /** Matching files, best first (see [pick]). */
    internal fun ranked(files: List<SlskFile>, title: String, artist: String, durationSec: Int): List<SlskFile> {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val wantTitle = key(baseTitle(title))
        val wantArtist = key(artist.substringBefore(","))
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return emptyList()
        val titleLower = title.lowercase()
        return files.filter { f ->
            val name = f.displayName.lowercase()
            f.isFlac && f.size > 0 &&
                key(name).contains(wantTitle) &&
                key(f.filename).contains(wantArtist) &&
                otherVersion.findAll(name).all { titleLower.contains(it.value) } &&
                (durationSec <= 0 || f.durationSec <= 0 || kotlin.math.abs(f.durationSec - durationSec) <= 5)
        }.sortedWith(
            // CD quality (~40 MB) before hi-res (a 24/96 copy was 133 MB), then the best peer.
            compareByDescending<SlskFile> { it.slotsFree }
                .thenByDescending { it.bitDepth <= 16 && it.sampleRate <= 48_000 }
                .thenByDescending { it.score() },
        )
    }

    /** A real FLAC of the expected length, not a sample or a wrong upload. */
    private fun isRightTrack(file: File, durationSec: Int): Boolean {
        val seconds = runCatching { file.inputStream().use { flacLengthSec(it.readNBytes(64 * 1024)) } }.getOrNull()
        val ok = seconds != null && (durationSec <= 0 || kotlin.math.abs(seconds - durationSec) <= 5)
        if (!ok) Log.d(TAG, "${file.name}: length ${seconds ?: "unreadable"}s, expected ${durationSec}s")
        return ok
    }

    /**
     * Length from a FLAC's STREAMINFO. Shared files often start with an ID3v2 tag
     * (artist/title/cover), which puts "fLaC" after it rather than at byte 0.
     */
    internal fun flacLengthSec(head: ByteArray): Int? {
        var start = 0
        if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            // Tag size: 4 "syncsafe" bytes (7 bits each), plus the 10-byte header and an optional footer.
            val size = (0..3).fold(0) { acc, i -> (acc shl 7) or (head[6 + i].toInt() and 0x7F) }
            start = 10 + size + (if (head[5].toInt() and 0x10 != 0) 10 else 0)
        }
        if (start + 42 > head.size) return null
        return LosslessSource.flacDurationSec(head.copyOfRange(start, start + 42))
    }

    /** Keep the cache under its limit, dropping the least recently used files. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles { f -> f.extension == "flac" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
