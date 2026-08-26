package com.music.spotui.deezer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher

/**
 * ExoPlayer [DataSource] for Deezer's `deezer://` streams. Deezer serves the
 * audio from its CDN with every 3rd 2048-byte block Blowfish-encrypted, so it
 * can't be handed to [androidx.media3.datasource.DefaultHttpDataSource] directly.
 * This source range-fetches the CDN bytes and decrypts them on the fly, so
 * ExoPlayer sees plain MP3/FLAC. The byte-offset alignment (round the requested
 * position down to a 2048 boundary and drop the remainder) mirrors ReFreezer's
 * `StreamServer`.
 *
 * URI shape (built by [DeezerSource]):
 *   deezer://stream?u=<url-encoded CDN url>&id=<trackId>&enc=<0|1>&fmt=<mp3|flac>
 */
@OptIn(UnstableApi::class)
internal class DeezerDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var cipher: Cipher? = null

    private var encrypted = false
    private var chunkCounter = 0
    private var dropFirst = 0

    // Decrypted-and-trimmed bytes not yet handed to the reader.
    private var pending = ByteArray(0)
    private var pendingPos = 0

    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val cdnUrl = dataSpec.uri.getQueryParameter("u")
            ?: throw java.io.IOException("Deezer stream URI missing 'u'")
        val trackId = dataSpec.uri.getQueryParameter("id").orEmpty()
        encrypted = dataSpec.uri.getQueryParameter("enc") != "0"

        val position = dataSpec.position
        val alignedStart: Long
        if (encrypted) {
            dropFirst = (position % 2048).toInt()
            alignedStart = position - dropFirst
            chunkCounter = (alignedStart / 2048).toInt()
            cipher = DeezerCrypto.cipher(DeezerCrypto.trackKey(trackId))
        } else {
            dropFirst = 0
            alignedStart = position
        }

        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length - 1 else -1L
        val conn = (URL(cdnUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "*")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Range", "bytes=$alignedStart-${if (end < 0) "" else end}")
        }
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("Deezer CDN HTTP $code")
        }
        connection = conn
        input = conn.inputStream

        val contentLength = conn.contentLengthLong // bytes available from alignedStart
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength >= 0 -> (contentLength - dropFirst).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        pending = ByteArray(0)
        pendingPos = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (pendingPos >= pending.size) {
            if (!fillPending()) return C.RESULT_END_OF_INPUT
        }
        val available = pending.size - pendingPos
        val toCopy = minOf(length, available, remainingOrMax())
        if (toCopy <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(pending, pendingPos, buffer, offset, toCopy)
        pendingPos += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    /** Read the next 2048 chunk from the CDN, decrypt/trim it into [pending]. */
    private fun fillPending(): Boolean {
        val stream = input ?: return false
        val chunk = ByteArray(2048)
        var total = 0
        while (total < 2048) {
            val r = stream.read(chunk, total, 2048 - total)
            if (r == -1) break
            total += r
        }
        if (total == 0) return false

        var out = chunk
        if (encrypted && total == 2048) {
            if (chunkCounter % 3 == 0) out = cipher!!.doFinal(chunk)
            chunkCounter++
        }
        // Partial final chunk (and unencrypted streams) pass through unchanged.
        var start = 0
        var len = total
        if (dropFirst > 0) {
            if (dropFirst >= total) {
                dropFirst -= total
                pending = ByteArray(0)
                pendingPos = 0
                return fillPending()
            }
            start = dropFirst
            len = total - dropFirst
            dropFirst = 0
        }
        pending = if (start == 0 && len == out.size) out else out.copyOfRange(start, start + len)
        pendingPos = 0
        return pending.isNotEmpty()
    }

    private fun remainingOrMax(): Int =
        if (bytesRemaining == C.LENGTH_UNSET.toLong()) Int.MAX_VALUE
        else bytesRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        } finally {
            input = null
            runCatching { connection?.disconnect() }
            connection = null
            cipher = null
            pending = ByteArray(0)
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        const val SCHEME = "deezer"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/79.0.3945.130 Safari/537.36"
    }
}

/**
 * Routes `deezer://` URIs to [DeezerDataSource] and everything else to
 * [defaultFactory] (the app's cached HTTP stack). Installed as the player's
 * media data source so both Deezer and normal streams share one player.
 */
@OptIn(UnstableApi::class)
internal class DeezerAwareDataSourceFactory(
    private val defaultFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        DispatchingDataSource(defaultFactory.createDataSource(), DeezerDataSource())
}

@OptIn(UnstableApi::class)
private class DispatchingDataSource(
    private val default: DataSource,
    private val deezer: DeezerDataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        deezer.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == DeezerDataSource.SCHEME) deezer else default
        return active!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
