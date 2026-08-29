package com.music.spotui.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * BitChord-style bounded-range wrapper for googlevideo streams.
 *
 * YouTube paces open-ended responses close to playback speed. Reopening the
 * same logical stream as 2 MiB ranges lets Media3 build a stable buffer and
 * avoids the repeated play/stall cycle.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (dataSpec.length != C.LENGTH_UNSET.toLong() || total == null) {
            passthrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }

        passthrough = false
        bytesRemaining = (total - position).coerceAtLeast(0L)
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val length = minOf(chunkBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            upstream.open(spec)
        } catch (error: Exception) {
            Log.w(TAG, "range $position-${position + length - 1} refused for ${spec.uri.getQueryParameter("c")}", error)
            throw error
        }
        chunkRemaining = length
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    private companion object {
        const val TAG = "YouTubeRange"
        const val MAX_EMPTY_RANGES = 3
    }
}
