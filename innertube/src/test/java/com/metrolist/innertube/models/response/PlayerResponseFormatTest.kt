package com.metrolist.innertube.models.response

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResponseFormatTest {
    private fun format(autoDubbed: Boolean?) = PlayerResponse.StreamingData.Format(
        itag = 251,
        url = "https://example.test/audio",
        mimeType = "audio/webm; codecs=\"opus\"",
        bitrate = 140_000,
        width = null,
        height = null,
        contentLength = 1_000,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = "1000",
        audioSampleRate = 48_000,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        cipher = null,
        audioTrack = PlayerResponse.StreamingData.Format.AudioTrack(
            displayName = "Original",
            id = "en.0",
            isAutoDubbed = autoDubbed,
        ),
    )

    @Test fun explicitFalseMeansOriginalAudio() = assertTrue(format(false).isOriginal)

    @Test fun missingDubFlagMeansOriginalAudio() = assertTrue(format(null).isOriginal)

    @Test fun autoDubbedAudioIsRejected() = assertFalse(format(true).isOriginal)
}
