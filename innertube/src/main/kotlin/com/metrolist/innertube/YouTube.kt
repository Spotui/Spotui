package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.response.SearchResponse
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.SearchPage
import com.metrolist.innertube.pages.SearchResult
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.Proxy

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic).
 *
 * Trimmed for spotui: only the pieces the app uses survive — track search
 * (to match a Spotify track to a YouTube video), the player endpoint (to
 * resolve the audio stream) and the NewPipe fallback deobfuscation.
 */
object YouTube {
    private val innerTube = InnerTube()

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, query, filter.value.ifEmpty { null }).body<SearchResponse>()
        val contents = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents
            .orEmpty()

        val items = mutableListOf<YTItem>()

        // 1. Parse Top Result cards (musicCardShelfRenderer) - e.g. official artist audio/video
        contents.mapNotNull { it.musicCardShelfRenderer }.forEach { card ->
            SearchPage.fromMusicCardShelfRenderer(card)?.let { items.add(it) }
            card.contents?.mapNotNull { it.musicResponsiveListItemRenderer }?.mapNotNull { SearchPage.toYTItem(it) }?.let {
                items.addAll(it)
            }
        }

        // 2. Parse standard music shelves
        contents.mapNotNull { it.musicShelfRenderer }.forEach { shelf ->
            shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) }?.let { items.addAll(it) }
        }

        // 3. Parse modern itemSectionRenderer contents
        contents.mapNotNull { it.itemSectionRenderer }.forEach { section ->
            section.contents?.mapNotNull { it.musicResponsiveListItemRenderer }?.mapNotNull { SearchPage.toYTItem(it) }?.let {
                items.addAll(it)
            }
            section.contents?.mapNotNull { it.musicShelfRenderer }?.forEach { shelf ->
                shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) }?.let { items.addAll(it) }
            }
        }

        val continuations = contents.mapNotNull { it.musicShelfRenderer }.firstOrNull { it.continuations != null }
            ?.continuations?.getContinuation()

        SearchResult(
            items = items.distinctBy { it.id },
            continuation = continuations
        )
    }

    suspend fun searchGeneral(query: String): Result<SearchResult> = search(query, SearchFilter.FILTER_ALL)

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
        val items = response.continuationContents?.musicShelfContinuation?.contents
            ?.mapNotNull {
                SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
            } ?: emptyList()
        SearchResult(
            items = items,
            continuation = if (items.isEmpty()) null else response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
        )
    }

    suspend fun player(videoId: String, playlistId: String? = null, client: YouTubeClient, signatureTimestamp: Int? = null, poToken: String? = null, authenticated: Boolean = false): Result<PlayerResponse> = runCatching {
        innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken, authenticated).body<PlayerResponse>()
    }

    suspend fun visitorData(): Result<String> = runCatching {
        Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
            .jsonArray[0]
            .jsonArray[2]
            .jsonArray.first {
                (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                    VISITOR_DATA_REGEX.containsMatchIn(candidate)
                } ?: false
            }
            .jsonPrimitive.content
    }

    /**
     * Validates that the current cookie/session tuple resolves an active YouTube account.
     * A 200 response alone is not enough: the endpoint can return a signed-out menu.
     */
    suspend fun validateLogin(): Result<Boolean> = runCatching {
        val body = innerTube.accountMenu(WEB_REMIX).bodyAsText()
        body.contains("activeAccountHeaderRenderer") ||
            (body.contains("accountName") && !body.contains("Sign in"))
    }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALL = SearchFilter("")
        }
    }

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    fun getNewPipeStreamUrls(videoId: String): List<Pair<Int, String>> {
        return NewPipeExtractor.newPipePlayer(videoId)
    }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
    ): PlayerResponse? {
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        }

        val streamsList = getNewPipeStreamUrls(videoId)
        if (streamsList.isEmpty()) return null

        val decodedSigResponse = tempRes.copy(
            streamingData = tempRes.streamingData?.copy(
                formats = tempRes.streamingData.formats?.map { format ->
                    format.copy(
                        url = streamsList.find { it.first == format.itag }?.second ?: format.url,
                    )
                },
                adaptiveFormats = tempRes.streamingData.adaptiveFormats.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url,
                    )
                },
            ),
        )

        val urlList = (
            decodedSigResponse.streamingData?.adaptiveFormats?.mapNotNull { it.url }?.toMutableList() ?: mutableListOf()
        ).apply {
            decodedSigResponse.streamingData?.formats?.mapNotNull { it.url }?.let { addAll(it) }
        }

        return if (urlList.isNotEmpty()) {
            decodedSigResponse
        } else {
            null
        }
    }
}
