package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.ResponseContext
import com.metrolist.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PlayerResponse with [com.metrolist.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
data class PlayerResponse(
    val responseContext: ResponseContext? = null,
    val playabilityStatus: PlayabilityStatus = PlayabilityStatus("OK", null),
    val playerConfig: PlayerConfig? = null,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
    @SerialName("playbackTracking")
    val playbackTracking: PlaybackTracking? = null,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String? = null,
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig? = null,
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double? = null,
            val perceptualLoudnessDb: Double? = null,
        )
    }

    @Serializable
    data class StreamingData(
        val formats: List<Format>? = null,
        val adaptiveFormats: List<Format> = emptyList(),
        val expiresInSeconds: Int = 0,
    ) {
        @Serializable
        data class Format(
            val itag: Int = 0,
            val url: String? = null,
            val mimeType: String = "",
            val bitrate: Int = 0,
            val width: Int? = null,
            val height: Int? = null,
            val contentLength: Long? = null,
            val quality: String = "",
            val fps: Int? = null,
            val qualityLabel: String? = null,
            val averageBitrate: Int? = null,
            val audioQuality: String? = null,
            val loudnessDb: Double? = null,
            val lastModified: Long? = null,
            val signatureCipher: String? = null,
            val cipher: String? = null,
            val audioTrack: AudioTrack? = null
        ) {
            val isAudio: Boolean
                get() = width == null || mimeType.startsWith("audio/")
            val isOriginal: Boolean
                get() = audioTrack?.isAutoDubbed == null

            @Serializable
            data class AudioTrack(
                val displayName: String?,
                val id: String?,
                val isAutoDubbed: Boolean?,
            )
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String,
        val title: String?,
        val author: String?,
        val channelId: String,
        val lengthSeconds: String,
        val musicVideoType: String?,
        val viewCount: String?,
        val thumbnail: Thumbnails,
    )

    @Serializable
    data class PlaybackTracking(
        @SerialName("videostatsPlaybackUrl")
        val videostatsPlaybackUrl: VideostatsPlaybackUrl?,
        @SerialName("videostatsWatchtimeUrl")
        val videostatsWatchtimeUrl: VideostatsWatchtimeUrl?,
        @SerialName("atrUrl")
        val atrUrl: AtrUrl?,
    ) {
        @Serializable
        data class VideostatsPlaybackUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
        @Serializable
        data class VideostatsWatchtimeUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
        @Serializable
        data class AtrUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
    }
}
