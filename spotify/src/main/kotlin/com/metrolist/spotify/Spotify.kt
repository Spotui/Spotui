package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistOwner
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifyPlaylistTracksRef
import com.metrolist.spotify.models.SpotifyRecommendations
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifySearchResult
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.spotify.models.SpotifyUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Spotify API client that uses the internal GraphQL API (api-partner.spotify.com)
 * for most operations, falling back to the public REST API (api.spotify.com/v1/)
 * only for endpoints without a GraphQL equivalent (top tracks/artists,
 * recommendations, related artists).
 *
 * GraphQL persisted-query hashes sourced from:
 * https://github.com/sonic-liberation/hetu_spotify_gql_client
 */
object Spotify {
    @Volatile
    var accessToken: String? = null

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"

    private fun randomUserAgent(): String {
        val osOptions = arrayOf(
            "Windows NT 10.0; Win64; x64",
            "Macintosh; Intel Mac OS X 10_15_7",
            "X11; Linux x86_64",
        )
        val chromeBase = 140
        val chromeMajor = chromeBase - (0..4).random()
        val chromePatch = (0..499).random()
        val os = osOptions.random()
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.$chromePatch.0 Safari/537.36"
    }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    private val restClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                url("https://api.spotify.com/v1/")
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
            }
            expectSuccess = false
        }
    }

    private val gqlClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            defaultRequest {
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    class SpotifyException(
        val statusCode: Int,
        override val message: String,
        val retryAfterSec: Long = 0,
    ) : Exception(message)

    @Volatile
    var logger: ((level: String, message: String) -> Unit)? = null

    private fun log(
        level: String,
        message: String,
    ) {
        logger?.invoke(level, message)
    }

    // ── JSON navigation helpers ──────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonObject
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.str(key: String): String? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.int(key: String): Int? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.bool(key: String): Boolean? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.arr(key: String): JsonArray? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonArray
        } catch (_: Exception) {
            null
        }

    // ── GraphQL core ─────────────────────────────────────────────────────

    /**
     * Callback invoked when a GQL hash is rejected (PersistedQueryNotFound).
     * The app module sets this to trigger a remote hash refresh.
     */
    @Volatile
    var onHashExpired: ((operationName: String) -> Unit)? = null

    private suspend fun graphqlPost(
        operationName: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val token =
            accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                log("E", "GQL $operationName — no token")
            }

        val primaryHash = SpotifyHashProvider.getHash(operationName)
        val hashCandidates = buildList {
            add(primaryHash)
            SpotifyHashProvider.getPreviousHash(operationName)?.let { prev ->
                if (prev != primaryHash) add(prev)
            }
        }

        for ((hashIdx, sha256Hash) in hashCandidates.withIndex()) {
            val body = buildGqlBody(operationName, sha256Hash, variables)
            val result = executeGqlWithRetries(operationName, token, body)

            if (result.isPersistedQueryNotFound) {
                if (hashIdx < hashCandidates.lastIndex) {
                    log("W", "GQL $operationName hash rejected, trying previous_hash")
                    continue
                }
                log("E", "GQL $operationName all known hashes rejected, triggering remote refresh")
                onHashExpired?.invoke(operationName)
                throw SpotifyException(412, "PersistedQueryNotFound for $operationName — hash may have rotated")
            }

            return result.json!!
        }

        throw SpotifyException(412, "No valid hash found for $operationName")
    }

    private suspend fun spclientPost(
        path: String,
        body: JsonObject,
        token: String,
    ): JsonObject {
        val url = if (path.startsWith("http")) path else "https://spclient.wg.spotify.com/$path"
        val response = gqlClient.post(url) {
            header("Authorization", "Bearer $token")
            setBody(TextContent(body.toString(), ContentType.Application.Json.withParameter("charset", "UTF-8")))
        }
        if (response.status.value !in 200..299) {
            log("W", "spclientPost($path) HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            throw SpotifyException(response.status.value, "spclientPost($path) HTTP ${response.status.value}")
        }
        val text = response.bodyAsText()
        return if (text.isBlank()) buildJsonObject {} else json.parseToJsonElement(text).jsonObject
    }

    private suspend fun spclientGet(
        path: String,
        token: String,
    ): JsonObject {
        val url = if (path.startsWith("http")) path else "https://spclient.wg.spotify.com/$path"
        val response = gqlClient.get(url) {
            header("Authorization", "Bearer $token")
        }
        if (response.status.value !in 200..299) {
            log("W", "spclientGet($path) HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            throw SpotifyException(response.status.value, "spclientGet($path) HTTP ${response.status.value}")
        }
        val text = response.bodyAsText()
        return if (text.isBlank()) buildJsonObject {} else json.parseToJsonElement(text).jsonObject
    }

    private fun buildGqlBody(
        operationName: String,
        sha256Hash: String,
        variables: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("variables", variables)
            put("operationName", operationName)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", sha256Hash)
                }
            }
        }

    private class GqlResult(
        val json: JsonObject?,
        val isPersistedQueryNotFound: Boolean,
    )

    private suspend fun executeGqlWithRetries(
        operationName: String,
        token: String,
        body: JsonObject,
    ): GqlResult {
        val maxRetries = 3
        for (attempt in 0 until maxRetries) {
            log(
                "D",
                "GQL POST $operationName (token: ${token.take(8)}...)" +
                    if (attempt > 0) " [retry $attempt]" else "",
            )

            val response =
                gqlClient.post(GQL_URL) {
                    header("Authorization", "Bearer $token")
                    setBody(
                        TextContent(
                            body.toString(),
                            ContentType.Application.Json.withParameter("charset", "UTF-8"),
                        ),
                    )
                }

            log("D", "GQL POST $operationName -> ${response.status.value}")

            if (response.status == HttpStatusCode.Unauthorized) {
                throw SpotifyException(401, "Token expired or invalid")
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                if (attempt < maxRetries - 1) {
                    log("W", "GQL $operationName -> 429, waiting ${retryAfter}s (attempt ${attempt + 1}/$maxRetries)")
                    delay(retryAfter * 1000)
                    continue
                }
                throw SpotifyException(429, "Rate limited", retryAfterSec = retryAfter)
            }
            if (response.status == HttpStatusCode.PreconditionFailed) {
                return GqlResult(json = null, isPersistedQueryNotFound = true)
            }
            if (response.status.value !in 200..299) {
                val bodyText = response.bodyAsText()
                log("E", "GQL $operationName FAILED: ${response.status.value} — ${bodyText.take(200)}")
                throw SpotifyException(response.status.value, "GraphQL error ${response.status.value}: $bodyText")
            }

            val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val errors = responseJson.arr("errors")
            if (errors != null && errors.isNotEmpty()) {
                val errorMsg = errors[0].jsonObject.str("message") ?: "Unknown GraphQL error"
                if (errorMsg.contains("PersistedQueryNotFound", ignoreCase = true)) {
                    return GqlResult(json = null, isPersistedQueryNotFound = true)
                }
                log("E", "GQL $operationName returned error: $errorMsg")
                throw SpotifyException(400, "GraphQL: $errorMsg")
            }

            return GqlResult(json = responseJson, isPersistedQueryNotFound = false)
        }

        throw SpotifyException(429, "Rate limited after $maxRetries retries")
    }

    // ── REST core (fallback for endpoints without GQL equivalent) ────────

    private suspend inline fun <reified T> authenticatedGet(
        endpoint: String,
        failFastOn429: Boolean = false,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val token =
            accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                log("E", "REST $endpoint — no token")
            }

        val maxRetries = if (failFastOn429) 1 else 3
        val maxRetryDelaySec = 3L
        for (attempt in 0 until maxRetries) {
            log(
                "D",
                "REST GET $endpoint (token: ${token.take(8)}...)" +
                    if (attempt > 0) " [retry $attempt]" else "",
            )
            val response =
                restClient.get(endpoint) {
                    header("Authorization", "Bearer $token")
                    block()
                }
            log("D", "REST GET $endpoint -> ${response.status.value}")

            if (response.status == HttpStatusCode.Unauthorized) {
                throw SpotifyException(401, "Token expired or invalid")
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                if (failFastOn429 || retryAfter > maxRetryDelaySec) {
                    log("W", "REST $endpoint -> 429, failing fast (retryAfter=${retryAfter}s)")
                    throw SpotifyException(429, "Rate limited", retryAfterSec = retryAfter)
                }
                if (attempt < maxRetries - 1) {
                    log("W", "REST $endpoint -> 429, waiting ${retryAfter}s (attempt ${attempt + 1}/$maxRetries)")
                    delay(retryAfter * 1000)
                    continue
                }
                throw SpotifyException(429, "Rate limited", retryAfterSec = retryAfter)
            }
            if (response.status.value !in 200..299) {
                val bodyText = response.bodyAsText()
                log("E", "REST $endpoint FAILED: ${response.status.value} — ${bodyText.take(200)}")
                throw SpotifyException(response.status.value, "Spotify API error ${response.status.value}: $bodyText")
            }
            return response.body()
        }

        throw SpotifyException(429, "Rate limited after $maxRetries retries")
    }

    // ── GQL response converters ──────────────────────────────────────────

    private fun parseGqlImage(source: JsonObject): SpotifyImage? {
        val url = source.str("url") ?: return null
        return SpotifyImage(url = url, height = source.int("height"), width = source.int("width"))
    }

    private fun parseGqlImages(sources: JsonArray?): List<SpotifyImage> =
        sources?.mapNotNull { parseGqlImage(it.jsonObject) } ?: emptyList()

    private fun parseGqlSimpleArtist(artistObj: JsonObject): SpotifySimpleArtist? {
        val uri = artistObj.str("uri") ?: return null
        return SpotifySimpleArtist(
            id = uri.substringAfterLast(":"),
            name = artistObj.obj("profile")?.str("name") ?: "",
            uri = uri,
        )
    }

    /**
     * Parses the common track data structure shared across multiple GQL
     * operations (fetchPlaylist, fetchLibraryTracks, queryArtistOverview, etc.).
     *
     * @param albumOverride When non-null, used instead of the `albumOfTrack`
     *   field (needed for album-track responses where no albumOfTrack is present).
     * @param uriOverride When non-null, used as the track URI instead of
     *   reading it from [trackData]. Needed when the URI lives on a wrapper
     *   object (e.g. `track._uri`) rather than inside `track.data`.
     */
    private fun parseGqlTrack(
        trackData: JsonObject,
        albumOverride: SpotifySimpleAlbum? = null,
        uriOverride: String? = null,
    ): SpotifyTrack {
        val uri = uriOverride
            ?: trackData.str("uri")
            ?: trackData.str("_uri")
            ?: ""
        val trackId = uri.substringAfterLast(":")

        val artists =
            trackData.obj("artists")?.arr("items")?.mapNotNull { elem ->
                parseGqlSimpleArtist(elem.jsonObject)
            } ?: emptyList()

        val album =
            albumOverride ?: run {
                val albumData = trackData.obj("albumOfTrack")
                val albumUri = albumData?.str("uri") ?: ""
                val albumId = albumUri.substringAfterLast(":")
                SpotifySimpleAlbum(
                    id = albumId,
                    name = albumData?.str("name") ?: "",
                    images = parseGqlImages(albumData?.obj("coverArt")?.arr("sources")),
                    uri = albumUri.ifEmpty { null },
                )
            }

        return SpotifyTrack(
            id = trackId,
            name = trackData.str("name") ?: "",
            artists = artists,
            album = album,
            durationMs = parseGqlTrackDurationMs(trackData),
            // Spotify's GQL payload nests this value, unlike REST's flat
            // `explicit` boolean. Without it every GQL track inherited the
            // model default false and YouTube matching preferred clean edits.
            explicit = trackData.obj("contentRating")
                ?.str("label")
                ?.equals("EXPLICIT", ignoreCase = true) == true,
            uri = uri.ifEmpty { null },
        )
    }

    /**
     * Extracts track duration in ms from GQL track payload.
     * Tries multiple keys because different operations may return duration
     * as nested (duration.totalMilliseconds) or flat (durationMs / duration_ms).
     */
    private fun parseGqlTrackDurationMs(trackData: JsonObject): Int {
        trackData.obj("duration")?.int("totalMilliseconds")?.let { if (it > 0) return it }
        trackData.int("durationMs")?.let { if (it > 0) return it }
        trackData.int("duration_ms")?.let { if (it > 0) return it }
        // Some APIs return duration in seconds
        trackData.int("duration")?.let { sec -> if (sec > 0) return sec * 1000 }
        return 0
    }

    /**
     * Flattens the nested `images.items[].sources[]` structure used by
     * playlists in the GQL response.
     */
    private fun parseGqlPlaylistImages(imagesObj: JsonObject?): List<SpotifyImage> =
        imagesObj?.arr("items")?.flatMap { imageGroup ->
            parseGqlImages(imageGroup.jsonObject.arr("sources"))
        } ?: emptyList()

    // ── User Profile (GQL with REST fallback) ──────────────────────────

    suspend fun me(): Result<SpotifyUser> =
        runCatching {
            try {
                val response =
                    graphqlPost(
                        operationName = "profileAttributes",
                    )
                val profile =
                    response.obj("data")?.obj("me")?.obj("profile")
                        ?: throw SpotifyException(500, "Invalid profileAttributes response")

                val uri = profile.str("uri") ?: ""
                SpotifyUser(
                    id = uri.substringAfterLast(":"),
                    displayName = profile.str("name"),
                    email = null,
                    images = parseGqlImages(profile.obj("avatar")?.arr("sources")),
                )
            } catch (e: Exception) {
                log("W", "GQL me() failed, falling back to REST: ${e.message}")
                authenticatedGet<SpotifyUser>("me")
            }
        }

    // ── Lyrics (color-lyrics — the official synced lyrics endpoint) ─────

    /**
     * The exact synced lyrics the official Spotify client shows, fetched from
     * spclient's color-lyrics endpoint with the web-player token. 404 = the
     * track simply has no lyrics on Spotify.
     */
    suspend fun lyrics(trackId: String): Result<com.metrolist.spotify.models.SpotifyLyrics> =
        runCatching {
            val token =
                accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                    log("E", "lyrics($trackId) — no token")
                }
            val response = gqlClient.get(
                "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId" +
                    "?format=json&vocalRemoval=false&market=from_token",
            ) {
                header("Authorization", "Bearer $token")
            }
            if (response.status.value !in 200..299) {
                log("W", "lyrics($trackId) HTTP ${response.status.value}")
                throw SpotifyException(response.status.value, "color-lyrics HTTP ${response.status.value}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val lyricsObj =
                root.obj("lyrics") ?: throw SpotifyException(500, "Invalid color-lyrics response")
            val synced = lyricsObj.str("syncType") == "LINE_SYNCED"
            val lines =
                lyricsObj.arr("lines")?.mapNotNull { el ->
                    val o = el.jsonObject
                    val words = o.str("words")?.takeIf { it.isNotBlank() && it != "♪" } ?: return@mapNotNull null
                    com.metrolist.spotify.models.SpotifyLyricLine(
                        startMs = o.str("startTimeMs")?.toLongOrNull() ?: 0L,
                        words = words,
                    )
                }.orEmpty()
            if (lines.isEmpty()) throw SpotifyException(404, "Empty lyrics for $trackId")
            log("D", "lyrics($trackId) OK — ${lines.size} lines, synced=$synced")
            com.metrolist.spotify.models.SpotifyLyrics(synced = synced, lines = lines)
        }

    // ── Playlists (GQL: libraryV3) ──────────────────────────────────────

    suspend fun myPlaylists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    // Ask Spotify to return every leaf playlist regardless of folder
                    // nesting. Without flatten=true the response only contains root-
                    // level items: top-level playlists plus FolderResponseWrapper
                    // entries whose contents are never expanded — the parser below
                    // ignores non-PlaylistResponseWrapper items, so anything inside
                    // a folder would otherwise be invisible (issues #46, #78).
                    put("flatten", true)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", false)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val rawItems = libraryData.arr("items").orEmpty()
            // Diagnostic mirror of myLibraryNode: collaborative playlists historically
            // surfaced under typenames the strict equality check below silently dropped
            // (issue #37). Logging the typename distribution lets us spot any future
            // schema drift without another round of bug reports.
            val typeCounts = mutableMapOf<String, Int>()
            rawItems.forEach { itemElem ->
                val wrapper = itemElem.jsonObject.obj("item")
                val tn = wrapper?.str("__typename") ?: "<missing>"
                typeCounts[tn] = (typeCounts[tn] ?: 0) + 1
            }
            log("D", "myPlaylists: ${rawItems.size} raw items, typename counts = $typeCounts")

            val playlists =
                rawItems.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    // Match every Playlist*Wrapper variant (Collaborative, etc.) like
                    // myLibraryNode/myArtists already do. The strict equality check
                    // dropped collaborative playlists silently.
                    if (typeName != "PlaylistResponseWrapper" &&
                        !typeName.contains("Playlist", ignoreCase = true)
                    ) return@mapNotNull null
                    parsePlaylistWrapper(wrapper)
                }

            SpotifyPaging(
                items = playlists,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    // ── Library hierarchy (GQL: libraryV3, folders preserved) ───────────

    /**
     * Returns one level of the user's library tree. When [folderUri] is null the
     * response is the library root: top-level playlists plus folder containers.
     * When [folderUri] is set, Spotify treats that folder as the root and returns
     * its direct children (which may include sub-folders).
     *
     * Use this for UIs that want to mirror the user's folder organization. For a
     * flat list of every playlist regardless of nesting, use [myPlaylists].
     */
    suspend fun myLibraryNode(
        folderUri: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<com.metrolist.spotify.models.SpotifyLibraryItem>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    // flatten=false preserves folder boundaries; folderUri scopes
                    // the response to a single level (null = root).
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    if (folderUri != null) put("folderUri", folderUri) else put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val rawItems = libraryData.arr("items").orEmpty()
            // Diagnostic: dump every wrapper's __typename so we can spot any
            // schema variation Spotify ships (the names have shifted historically).
            // Trim once we're confident the recognized set is stable.
            log("D", "myLibraryNode(folder=$folderUri): ${rawItems.size} raw items")
            val typeCounts = mutableMapOf<String, Int>()
            rawItems.forEach { itemElem ->
                val wrapper = itemElem.jsonObject.obj("item")
                val tn = wrapper?.str("__typename") ?: "<missing>"
                typeCounts[tn] = (typeCounts[tn] ?: 0) + 1
            }
            log("D", "myLibraryNode: typename counts = $typeCounts")

            val items =
                rawItems.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    when {
                        typeName == "PlaylistResponseWrapper" || typeName.contains("Playlist", ignoreCase = true) ->
                            parsePlaylistWrapper(wrapper)
                                ?.let { com.metrolist.spotify.models.SpotifyLibraryItem.Playlist(it) }
                        typeName == "FolderResponseWrapper" || typeName.contains("Folder", ignoreCase = true) ->
                            parseFolderWrapper(wrapper)
                                ?.let { com.metrolist.spotify.models.SpotifyLibraryItem.Folder(it) }
                                ?: run {
                                    // Folder typename matched but parsing returned null —
                                    // likely a shape we don't know. Dump the keys so we
                                    // can update parseFolderWrapper.
                                    log("W", "myLibraryNode: failed to parse folder wrapper, keys=${wrapper.keys}, dataKeys=${wrapper.obj("data")?.keys}")
                                    null
                                }
                        else -> {
                            log("D", "myLibraryNode: skipping unknown __typename='$typeName'")
                            null
                        }
                    }
                }

            SpotifyPaging(
                items = items,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    private fun parsePlaylistWrapper(wrapper: JsonObject): SpotifyPlaylist? {
        val data = wrapper.obj("data") ?: return null
        if (data.str("__typename") != "Playlist") return null
        val playlistUri = wrapper.str("_uri") ?: return null
        val playlistId = playlistUri.substringAfterLast(":")
        val ownerData = data.obj("ownerV2")?.obj("data")
        val ownerId = ownerData?.str("uri")?.substringAfterLast(":") ?: ownerData?.str("id") ?: ""
        return SpotifyPlaylist(
            id = playlistId,
            name = data.str("name") ?: "",
            description = data.str("description"),
            images = parseGqlPlaylistImages(data.obj("images")),
            owner = SpotifyPlaylistOwner(
                id = ownerId,
                displayName = ownerData?.str("name"),
                uri = ownerData?.str("uri"),
            ),
            uri = playlistUri,
        )
    }

    private fun parseFolderWrapper(wrapper: JsonObject): com.metrolist.spotify.models.SpotifyLibraryFolder? {
        val uri = wrapper.str("_uri") ?: return null
        // Spotify has shipped this object under several shapes over time; the name
        // and child count have lived in `data` and at the root of the wrapper.
        // Try both so we don't break on a future field reshuffle.
        val name = wrapper.obj("data")?.str("name")
            ?: wrapper.str("name")
            ?: return null
        val total = wrapper.obj("data")?.int("totalLength")
            ?: wrapper.obj("data")?.int("numberOfItems")
            ?: wrapper.int("totalLength")
            ?: 0
        return com.metrolist.spotify.models.SpotifyLibraryFolder(
            uri = uri,
            name = name,
            totalChildren = total,
        )
    }

    // ── Library Artists (GQL: libraryV3 with Artists filter) ───────────

    suspend fun myArtists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyArtist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Artists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val artists =
                libraryData.arr("items")?.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    if (!typeName.contains("Artist", ignoreCase = true)) return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null

                    val artistUri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
                    val artistId = artistUri.substringAfterLast(":")

                    val name = data.obj("profile")?.str("name")
                        ?: data.str("name")
                        ?: return@mapNotNull null

                    val images = data.obj("visuals")?.obj("avatarImage")
                        ?.arr("sources")?.let { parseGqlImages(it) }
                        ?: emptyList()

                    SpotifyArtist(
                        id = artistId,
                        name = name,
                        images = images,
                        uri = artistUri,
                    )
                } ?: emptyList()

            SpotifyPaging(
                items = artists,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    // ── Library Albums (GQL: libraryV3 with Albums filter) ─────────────

    /** The user's saved albums from their Spotify library. */
    suspend fun myAlbums(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyAlbum>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Albums") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val albums =
                libraryData.arr("items")?.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    if (!typeName.contains("Album", ignoreCase = true)) return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null

                    val albumUri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
                    val albumId = albumUri.substringAfterLast(":")
                    val name = data.str("name") ?: return@mapNotNull null

                    val artists =
                        data.obj("artists")?.arr("items")?.mapNotNull { a ->
                            val an = a.jsonObject.obj("profile")?.str("name") ?: return@mapNotNull null
                            val au = a.jsonObject.str("uri")
                            SpotifySimpleArtist(id = au?.substringAfterLast(":") ?: "", name = an)
                        } ?: emptyList()

                    SpotifyAlbum(
                        id = albumId,
                        name = name,
                        artists = artists,
                        images = parseGqlImages(data.obj("coverArt")?.arr("sources")),
                        releaseDate = data.obj("date")?.int("year")?.toString(),
                        uri = albumUri,
                    )
                } ?: emptyList()

            SpotifyPaging(
                items = albums,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    // ── Playlist detail (GQL: fetchPlaylist) ────────────────────────────

    suspend fun playlist(playlistId: String): Result<SpotifyPlaylist> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", 0)
                    put("limit", 25)
                    put("enableWatchFeedEntrypoint", true)
                }

            val response =
                graphqlPost(
                    operationName = "fetchPlaylist",
                    variables = vars,
                )

            val playlist =
                response.obj("data")?.obj("playlistV2")
                    ?: throw SpotifyException(500, "Invalid fetchPlaylist response")

            val ownerData = playlist.obj("ownerV2")?.obj("data")
            val ownerUri = ownerData?.str("uri") ?: ""

            val images =
                playlist.obj("images")?.arr("items")?.firstOrNull()?.let {
                    parseGqlImages(it.jsonObject.arr("sources"))
                } ?: emptyList()

            val basePerm = playlist.str("basePermission")
            val isPublic = (basePerm == "VIEWER")
            val canEdit = playlist.obj("currentUserCapabilities")?.bool("canAdministratePermissions") == true

            SpotifyPlaylist(
                id = playlistId,
                name = playlist.str("name") ?: "",
                description = playlist.str("description"),
                images = images,
                owner =
                    SpotifyPlaylistOwner(
                        id = ownerUri.substringAfterLast(":"),
                        displayName = ownerData?.str("name"),
                        uri = ownerUri.ifEmpty { null },
                    ),
                tracks = SpotifyPlaylistTracksRef(total = playlist.obj("content")?.int("totalCount") ?: 0),
                collaborative = (playlist.obj("members")?.arr("items")?.size ?: 0) > 1,
                public = isPublic,
                canEdit = canEdit,
            )
        }

    suspend fun playlistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylistTrack>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset)
                    put("limit", limit)
                    put("enableWatchFeedEntrypoint", false)
                }

            val response =
                graphqlPost(
                    operationName = "fetchPlaylist",
                    variables = vars,
                )

            val content =
                response.obj("data")?.obj("playlistV2")?.obj("content")
                    ?: throw SpotifyException(500, "No content in fetchPlaylist response")

            val tracks =
                content.arr("items")?.mapNotNull { elem ->
                    val itemWrapper = elem.jsonObject.obj("itemV2") ?: return@mapNotNull null
                    val itemData = itemWrapper.obj("data") ?: return@mapNotNull null
                    val wrapperUri = itemWrapper.str("_uri") ?: itemWrapper.str("uri")
                    val uid = elem.jsonObject.str("uid") ?: itemWrapper.str("uid")
                    SpotifyPlaylistTrack(
                        track = parseGqlTrack(itemData, uriOverride = wrapperUri),
                        uid = uid,
                    )
                } ?: emptyList()

            SpotifyPaging(
                items = tracks,
                total = content.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
            )
        }

    // ── Playlist Mutations (GQL) ──────────────────────────────────────

    /**
     * Adds tracks to a Spotify playlist via GQL mutation.
     * @param playlistId Playlist ID (without the `spotify:playlist:` prefix).
     * @param trackUris Full Spotify URIs, e.g. `["spotify:track:abc123"]`.
     */
    suspend fun addTracksToPlaylist(
        playlistId: String,
        trackUris: List<String>,
    ): Result<Unit> =
        runCatching {
            val vars = buildJsonObject {
                put("playlistUri", "spotify:playlist:$playlistId")
                putJsonArray("playlistItemUris") {
                    trackUris.forEach { add(it) }
                }
                putJsonObject("newPosition") {
                    put("moveType", "BOTTOM_OF_PLAYLIST")
                    put("fromUid", JsonNull)
                }
            }
            log("D", "addTracksToPlaylist: sending mutation for $playlistId with ${trackUris.size} tracks, vars=$vars")
            kotlinx.coroutines.withTimeout(20_000L) {
                graphqlPost(
                    operationName = "addToPlaylist",
                    variables = vars,
                )
            }
            log("D", "addTracksToPlaylist: added ${trackUris.size} tracks to $playlistId")
        }

    /**
     * Creates a new playlist for the current user via Spotify's internal
     * playlist/v2 service and adds it to their rootlist (library).
     */
    suspend fun createPlaylist(name: String): Result<SpotifyPlaylist> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val userId = me().getOrThrow().id
            if (userId.isBlank()) throw SpotifyException(500, "No user id for createPlaylist")

            val createPayload = buildJsonObject {
                putJsonArray("ops") {
                    addJsonObject {
                        put("kind", "UPDATE_LIST_ATTRIBUTES")
                        putJsonObject("updateListAttributes") {
                            putJsonObject("newAttributes") {
                                putJsonObject("values") {
                                    put("name", name)
                                }
                            }
                        }
                    }
                }
            }

            val createResp = spclientPost("playlist/v2/playlist", createPayload, token)
            val uri = createResp.str("uri") ?: throw SpotifyException(500, "No uri in createPlaylist response")
            val playlistId = uri.substringAfterLast(":")

            val rootlistPayload = buildJsonObject {
                putJsonArray("deltas") {
                    addJsonObject {
                        putJsonArray("ops") {
                            addJsonObject {
                                put("kind", "ADD")
                                putJsonObject("add") {
                                    put("addFirst", true)
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("uri", uri)
                                            putJsonObject("attributes") {
                                                put("timestamp", System.currentTimeMillis().toString())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        putJsonObject("info") {
                            putJsonObject("source") {
                                put("client", "WEBPLAYER")
                            }
                        }
                    }
                }
            }

            spclientPost("playlist/v2/user/$userId/rootlist/changes", rootlistPayload, token)
            log("D", "createPlaylist($name) OK — $playlistId ($uri)")

            SpotifyPlaylist(
                id = playlistId,
                name = name,
                uri = uri,
                images = emptyList(),
                owner = null,
                tracks = null,
            )
        }

    /**
     * Removes tracks from a Spotify playlist via GQL mutation.
     * Requires the playlist-scoped [uid] for each item
     * (returned by fetchPlaylist in each content item).
     */
    suspend fun removeTracksFromPlaylist(
        playlistId: String,
        items: List<PlaylistItemRef>,
    ): Result<Unit> =
        runCatching {
            val vars = buildJsonObject {
                put("playlistUri", "spotify:playlist:$playlistId")
                putJsonArray("uids") {
                    items.forEach { item -> add(item.uid) }
                }
            }
            graphqlPost(
                operationName = "removeFromPlaylist",
                variables = vars,
            )
            log("D", "removeTracksFromPlaylist: removed ${items.size} items from $playlistId")
        }

    /**
     * Moves items within a Spotify playlist via GQL mutation.
     * [uids] are playlist-scoped item identifiers returned by fetchPlaylist.
     * [beforeUid] is the uid of the item the moved items should be placed before,
     * or null to move to the end of the playlist.
     */
    suspend fun moveItemsInPlaylist(
        playlistId: String,
        uids: List<String>,
        beforeUid: String?,
    ): Result<Unit> =
        runCatching {
            val vars = buildJsonObject {
                put("playlistUri", "spotify:playlist:$playlistId")
                putJsonArray("uids") {
                    uids.forEach { add(it) }
                }
                putJsonObject("newPosition") {
                    if (beforeUid != null) {
                        put("moveType", "BEFORE_UID")
                        put("fromUid", beforeUid)
                    } else {
                        put("moveType", "BOTTOM_OF_PLAYLIST")
                        put("fromUid", JsonNull)
                    }
                }
            }
            graphqlPost(
                operationName = "moveItemsInPlaylist",
                variables = vars,
            )
            log("D", "moveItemsInPlaylist: moved ${uids.size} items (before=$beforeUid) in $playlistId")
        }

    /**
     * Renames a playlist and/or updates its description via Spotify's internal
     * playlist/v2 changes endpoint.
     */
    suspend fun editPlaylistAttributes(
        playlistId: String,
        newName: String? = null,
        newDescription: String? = null,
    ): Result<Unit> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val cleanId = playlistId.removePrefix("spotify:playlist:")
            val payload = buildJsonObject {
                putJsonArray("deltas") {
                    addJsonObject {
                        putJsonArray("ops") {
                            addJsonObject {
                                put("kind", "UPDATE_LIST_ATTRIBUTES")
                                putJsonObject("updateListAttributes") {
                                    putJsonObject("newAttributes") {
                                        putJsonObject("values") {
                                            if (!newName.isNullOrBlank()) put("name", newName)
                                            if (!newDescription.isNullOrBlank()) put("description", newDescription)
                                        }
                                        putJsonArray("noValue") {}
                                    }
                                }
                            }
                        }
                        putJsonObject("info") {
                            putJsonObject("source") {
                                put("client", "WEBPLAYER")
                            }
                        }
                    }
                }
            }

            spclientPost("playlist/v2/playlist/$cleanId/changes", payload, token)
            log("D", "editPlaylistAttributes: updated $cleanId (name=$newName, desc=$newDescription)")
        }

    /**
     * Deletes a user's playlist by marking it deletedByOwner and removing it
     * from their rootlist (library).
     */
    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val cleanId = playlistId.removePrefix("spotify:playlist:")
            val uri = "spotify:playlist:$cleanId"
            val userId = me().getOrThrow().id

            // 1. Mark deletedByOwner on the playlist entity
            runCatching {
                val markDelPayload = buildJsonObject {
                    putJsonArray("deltas") {
                        addJsonObject {
                            putJsonArray("ops") {
                                addJsonObject {
                                    put("kind", "UPDATE_LIST_ATTRIBUTES")
                                    putJsonObject("updateListAttributes") {
                                        putJsonObject("newAttributes") {
                                            putJsonObject("values") {
                                                put("deletedByOwner", true)
                                            }
                                            putJsonArray("noValue") {}
                                        }
                                    }
                                }
                            }
                            putJsonObject("info") {
                                putJsonObject("source") {
                                    put("client", "WEBPLAYER")
                                }
                            }
                        }
                    }
                }
                spclientPost("playlist/v2/playlist/$cleanId/changes", markDelPayload, token)
            }

            // 2. Remove from rootlist
            val remPayload = buildJsonObject {
                putJsonArray("deltas") {
                    addJsonObject {
                        putJsonArray("ops") {
                            addJsonObject {
                                put("kind", "REM")
                                putJsonObject("rem") {
                                    put("itemsAsKey", true)
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("uri", uri)
                                        }
                                    }
                                }
                            }
                        }
                        putJsonObject("info") {
                            putJsonObject("source") {
                                put("client", "WEBPLAYER")
                            }
                        }
                    }
                }
            }
            spclientPost("playlist/v2/user/$userId/rootlist/changes", remPayload, token)
            log("D", "deletePlaylist: deleted and removed $cleanId from rootlist")
        }

    /**
     * Sets a playlist's public/private visibility.
     * Public = VIEWER base permission + rootlist public=true.
     * Private = BLOCKED base permission + rootlist public=false.
     */
    suspend fun setPlaylistPublic(playlistId: String, isPublic: Boolean): Result<Unit> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val cleanId = playlistId.removePrefix("spotify:playlist:")
            val userId = me().getOrThrow().id

            // 1. Fetch current revision
            val currPerm = runCatching {
                spclientGet("playlist-permission/v1/playlist/$cleanId/permission/base", token)
            }.getOrNull()
            val revision = currPerm?.str("revision")

            // 2. Set permission base level with revision
            val permPayload = buildJsonObject {
                if (!revision.isNullOrBlank()) put("revision", revision)
                put("permissionLevel", if (isPublic) "VIEWER" else "BLOCKED")
            }
            spclientPost("playlist-permission/v1/playlist/$cleanId/permission/base", permPayload, token)

            // 3. Set public attribute in user rootlist
            val rootlistPayload = buildJsonObject {
                putJsonArray("deltas") {
                    addJsonObject {
                        putJsonArray("ops") {
                            addJsonObject {
                                put("kind", "UPDATE_ITEM_ATTRIBUTES")
                                putJsonObject("updateItemAttributes") {
                                    putJsonObject("newAttributes") {
                                        putJsonObject("values") {
                                            put("public", isPublic)
                                        }
                                        putJsonArray("noValue") {}
                                    }
                                    putJsonObject("item") {
                                        put("uri", "spotify:playlist:$cleanId")
                                    }
                                }
                            }
                        }
                        putJsonObject("info") {
                            putJsonObject("source") {
                                put("client", "WEBPLAYER")
                            }
                        }
                    }
                }
            }
            spclientPost("playlist/v2/user/$userId/rootlist/changes", rootlistPayload, token)
            log("D", "setPlaylistPublic($cleanId, public=$isPublic) OK")
        }

    /**
     * Reference to a specific item inside a playlist, needed for removal/reorder.
     */
    data class PlaylistItemRef(
        val uri: String,
        val uid: String,
    )

    // ── Liked Songs (GQL: fetchLibraryTracks) ───────────────────────────

    suspend fun likedSongs(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifySavedTrack>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                }

            val response =
                graphqlPost(
                    operationName = "fetchLibraryTracks",
                    variables = vars,
                )

            val tracksData =
                response.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
                    ?: throw SpotifyException(500, "Invalid fetchLibraryTracks response")

            val savedTracks =
                tracksData.arr("items")?.mapNotNull { elem ->
                    val trackWrapper = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    val trackData = trackWrapper.obj("data") ?: return@mapNotNull null
                    val wrapperUri = trackWrapper.str("_uri") ?: trackWrapper.str("uri")
                    SpotifySavedTrack(track = parseGqlTrack(trackData, uriOverride = wrapperUri))
                } ?: emptyList()

            SpotifyPaging(
                items = savedTracks,
                total = tracksData.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
            )
        }

    // ── Library Mutations (GQL: addToLibrary / removeFromLibrary + rootlist) ──

    /**
     * Saves tracks/albums/playlists to the user's Spotify library (like/follow).
     * Non-playlist items use GQL addToLibrary. Playlists/mixes are added to rootlist.
     * @param uris Full Spotify URIs, e.g. `["spotify:track:abc123"]` or `["spotify:playlist:xyz"]`.
     */
    suspend fun addToLibrary(uris: List<String>): Result<Unit> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val (playlistUris, otherUris) = uris.partition { it.startsWith("spotify:playlist:") }

            if (otherUris.isNotEmpty()) {
                val vars = buildJsonObject {
                    putJsonArray("libraryItemUris") {
                        otherUris.forEach { add(it) }
                    }
                }
                graphqlPost(
                    operationName = "addToLibrary",
                    variables = vars,
                )
                log("D", "addToLibrary: added ${otherUris.size} non-playlist items")
            }

            if (playlistUris.isNotEmpty()) {
                val userId = me().getOrThrow().id
                val now = System.currentTimeMillis().toString()
                val rootlistPayload = buildJsonObject {
                    putJsonArray("deltas") {
                        addJsonObject {
                            putJsonArray("ops") {
                                addJsonObject {
                                    put("kind", "ADD")
                                    putJsonObject("add") {
                                        put("addFirst", true)
                                        putJsonArray("items") {
                                            playlistUris.forEach { uri ->
                                                addJsonObject {
                                                    put("uri", uri)
                                                    putJsonObject("attributes") {
                                                        put("timestamp", now)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            putJsonObject("info") {
                                putJsonObject("source") {
                                    put("client", "WEBPLAYER")
                                }
                            }
                        }
                    }
                }
                spclientPost("playlist/v2/user/$userId/rootlist/changes", rootlistPayload, token)
                log("D", "addToLibrary: added ${playlistUris.size} playlists to rootlist")
            }
        }

    /**
     * Removes tracks/albums/playlists from the user's Spotify library (unlike/unfollow).
     * @param uris Full Spotify URIs, e.g. `["spotify:track:abc123"]` or `["spotify:playlist:xyz"]`.
     */
    suspend fun removeFromLibrary(uris: List<String>): Result<Unit> =
        runCatching {
            val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
            val (playlistUris, otherUris) = uris.partition { it.startsWith("spotify:playlist:") }

            if (otherUris.isNotEmpty()) {
                val vars = buildJsonObject {
                    putJsonArray("libraryItemUris") {
                        otherUris.forEach { add(it) }
                    }
                }
                graphqlPost(
                    operationName = "removeFromLibrary",
                    variables = vars,
                )
                log("D", "removeFromLibrary: removed ${otherUris.size} non-playlist items")
            }

            if (playlistUris.isNotEmpty()) {
                val userId = me().getOrThrow().id
                val rootlistPayload = buildJsonObject {
                    putJsonArray("deltas") {
                        addJsonObject {
                            putJsonArray("ops") {
                                addJsonObject {
                                    put("kind", "REM")
                                    putJsonObject("rem") {
                                        put("itemsAsKey", true)
                                        putJsonArray("items") {
                                            playlistUris.forEach { uri ->
                                                addJsonObject {
                                                    put("uri", uri)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            putJsonObject("info") {
                                putJsonObject("source") {
                                    put("client", "WEBPLAYER")
                                }
                            }
                        }
                    }
                }
                spclientPost("playlist/v2/user/$userId/rootlist/changes", rootlistPayload, token)
                log("D", "removeFromLibrary: removed ${playlistUris.size} playlists from rootlist")
            }
        }

    // ── Top Tracks (REST fallback — no GQL equivalent) ──────────────────

    suspend fun topTracks(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyTrack>> =
        runCatching {
            authenticatedGet("me/top/tracks", failFastOn429 = true) {
                parameter("time_range", timeRange)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    // ── Top Artists (REST fallback — no GQL equivalent) ─────────────────

    suspend fun topArtists(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyArtist>> =
        runCatching {
            authenticatedGet("me/top/artists", failFastOn429 = true) {
                parameter("time_range", timeRange)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    // ── Single track (REST: GET tracks/{id}) ────────────────────────────
    // Used to resolve a full SpotifyTrack (artists, album, popularity) from a
    // bare track id — e.g. to seed the recommendation engine.
    suspend fun track(trackId: String): Result<SpotifyTrack> =
        runCatching {
            authenticatedGet<SpotifyTrack>("tracks/$trackId")
        }

    // ── Podcasts (REST) ─────────────────────────────────────────────────
    // Catalog search + a show's episode list via the stable REST Web API. Uses
    // the same web bearer token as track/artist reads; episodes play through the
    // hidden Spotify web player (SpotifyWebPlayer.playEpisode).
    suspend fun searchPodcasts(query: String, limit: Int = 20): Result<com.metrolist.spotify.models.SpotifyPodcastSearchResult> =
        runCatching {
            authenticatedGet<com.metrolist.spotify.models.SpotifyPodcastSearchResult>("search") {
                parameter("q", query)
                parameter("type", "show,episode")
                parameter("limit", limit)
                parameter("market", "from_token")
            }
        }

    suspend fun showEpisodes(showId: String, limit: Int = 50): Result<SpotifyPaging<com.metrolist.spotify.models.SpotifyEpisode>> =
        runCatching {
            authenticatedGet<SpotifyPaging<com.metrolist.spotify.models.SpotifyEpisode>>("shows/$showId/episodes") {
                parameter("limit", limit)
                parameter("market", "from_token")
            }
        }

    suspend fun show(showId: String): Result<com.metrolist.spotify.models.SpotifyShow> =
        runCatching {
            authenticatedGet<com.metrolist.spotify.models.SpotifyShow>("shows/$showId") {
                parameter("market", "from_token")
            }
        }

    // ── Track radio (spclient inspiredby-mix — the web player's autoplay queue) ──

    /**
     * The exact radio queue Spotify's own web player builds after a single track:
     * spclient's inspiredby-mix `seed_to_playlist` resolves the seed track into a
     * station playlist (`spotify:playlist:37i9dQZF1E8…`), whose tracks are then
     * fetched via the regular fetchPlaylist GQL operation. The station usually
     * starts with the seed track itself; callers filter it out as needed.
     */
    suspend fun trackRadio(trackId: String, limit: Int = 50): Result<List<SpotifyTrack>> =
        runCatching {
            val token =
                accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                    log("E", "trackRadio($trackId) — no token")
                }
            val response = gqlClient.get(
                "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/spotify:track:$trackId" +
                    "?response-format=json",
            ) {
                header("Authorization", "Bearer $token")
            }
            if (response.status.value !in 200..299) {
                log("W", "trackRadio($trackId) seed_to_playlist HTTP ${response.status.value}")
                throw SpotifyException(response.status.value, "seed_to_playlist HTTP ${response.status.value}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val playlistUri =
                root.arr("mediaItems")?.firstOrNull()?.jsonObject?.str("uri")
                    ?: throw SpotifyException(500, "No station playlist for $trackId")
            val playlistId = playlistUri.substringAfterLast(":")
            val tracks = playlistTracks(playlistId, limit = limit).getOrThrow()
                .items.mapNotNull { it.track }.filter { it.id.isNotEmpty() }
            log("D", "trackRadio($trackId) OK — station $playlistId, ${tracks.size} tracks")
            tracks
        }

    /**
     * Spotify's *own* "you might also like" recommender for a single seed track,
     * via the `internalLinkRecommenderTrack` GQL operation (the SEO recommended
     * tracks shown on a track's web page). This is the cookie-token-compatible
     * replacement for the deprecated REST `/v1/recommendations` endpoint — same
     * backend Spotify uses on open.spotify.com.
     */
    suspend fun recommendedTracks(trackId: String): Result<List<SpotifyTrack>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:track:$trackId")
                }

            val response =
                graphqlPost(
                    operationName = "internalLinkRecommenderTrack",
                    variables = vars,
                )

            val items =
                response.obj("data")
                    ?.obj("seoRecommendedTrack")
                    ?.arr("items")
                    ?: JsonArray(emptyList())

            items.mapNotNull { elem ->
                val item = elem.jsonObject
                val trackObj = item.obj("data") ?: return@mapNotNull null
                val uri = item.str("_uri") ?: trackObj.str("uri")
                parseGqlTrack(trackObj, uriOverride = uri).takeIf { it.id.isNotEmpty() }
            }
        }

    // ── Recommendations (REST fallback — no GQL equivalent) ─────────────

    suspend fun recommendations(
        seedTrackIds: List<String> = emptyList(),
        seedArtistIds: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 50,
    ): Result<SpotifyRecommendations> =
        runCatching {
            authenticatedGet("recommendations") {
                if (seedTrackIds.isNotEmpty()) parameter("seed_tracks", seedTrackIds.joinToString(","))
                if (seedArtistIds.isNotEmpty()) parameter("seed_artists", seedArtistIds.joinToString(","))
                if (seedGenres.isNotEmpty()) parameter("seed_genres", seedGenres.joinToString(","))
                parameter("limit", limit)
            }
        }

    // ── Search (GQL: searchDesktop) ─────────────────────────────────────

    suspend fun search(
        query: String,
        types: List<String> = listOf("track"),
        limit: Int = 20,
        offset: Int = 0,
    ): Result<SpotifySearchResult> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("searchTerm", query)
                    put("offset", offset)
                    put("limit", limit)
                    put("numberOfTopResults", 5)
                    put("includeAudiobooks", true)
                    put("includeArtistHasConcertsField", false)
                    put("includePreReleases", false)
                    put("includeLocalConcertsField", false)
                    put("includeAuthors", false)
                }

            val response =
                graphqlPost(
                    operationName = "searchDesktop",
                    variables = vars,
                )

            val searchData =
                response.obj("data")?.obj("searchV2")
                    ?: throw SpotifyException(500, "Invalid searchDesktop response")

            log("D", "searchDesktop sections: ${searchData.keys}")
            searchData.obj("podcasts")?.arr("items")?.firstOrNull()?.let { log("D", "PODITEM: ${it.toString().take(700)}") }
            searchData.obj("episodes")?.arr("items")?.firstOrNull()?.let { log("D", "EPITEM: ${it.toString().take(700)}") }
            searchData.obj("audiobooks")?.arr("items")?.firstOrNull()?.let { log("D", "ABITEM: ${it.toString().take(700)}") }

            val tracksSection = searchData.obj("tracksV2")
            val trackItems =
                tracksSection?.arr("items")?.mapNotNull { elem ->
                    val itemWrapper = elem.jsonObject.obj("item") ?: return@mapNotNull null
                    if (itemWrapper.str("__typename") != "TrackResponseWrapper") return@mapNotNull null
                    val data = itemWrapper.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Track") return@mapNotNull null
                    val wrapperUri = itemWrapper.str("_uri") ?: itemWrapper.str("uri")
                    parseGqlTrack(data, uriOverride = wrapperUri)
                } ?: emptyList()

            val albumsSection = searchData.obj("albumsV2")
            val albumItems =
                albumsSection?.arr("items")?.mapNotNull { elem ->
                    val wrapper = elem.jsonObject
                    if (wrapper.str("__typename") != "AlbumResponseWrapper") return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Album") return@mapNotNull null
                    parseGqlSearchAlbum(data)
                } ?: emptyList()

            val artistsSection = searchData.obj("artists")
            val artistItems =
                artistsSection?.arr("items")?.mapNotNull { elem ->
                    val wrapper = elem.jsonObject
                    if (wrapper.str("__typename") != "ArtistResponseWrapper") return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Artist") return@mapNotNull null
                    parseGqlSearchArtist(data)
                } ?: emptyList()

            val playlistsSection = searchData.obj("playlists")
            val playlistItems =
                playlistsSection?.arr("items")?.mapNotNull { elem ->
                    val wrapper = elem.jsonObject
                    if (wrapper.str("__typename") != "PlaylistResponseWrapper") return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Playlist") return@mapNotNull null
                    parseGqlSearchPlaylist(data)
                } ?: emptyList()

            SpotifySearchResult(
                tracks =
                    SpotifyPaging(
                        items = trackItems,
                        total = tracksSection?.int("totalCount") ?: 0,
                        limit = limit,
                        offset = offset,
                    ),
                albums =
                    if (albumItems.isNotEmpty()) {
                        SpotifyPaging(items = albumItems, total = albumsSection?.int("totalCount") ?: 0, limit = limit, offset = offset)
                    } else {
                        null
                    },
                artists =
                    if (artistItems.isNotEmpty()) {
                        SpotifyPaging(items = artistItems, total = artistsSection?.int("totalCount") ?: 0, limit = limit, offset = offset)
                    } else {
                        null
                    },
                playlists =
                    if (playlistItems.isNotEmpty()) {
                        SpotifyPaging(items = playlistItems, total = playlistsSection?.int("totalCount") ?: 0, limit = limit, offset = offset)
                    } else {
                        null
                    },
            )
        }

    private fun parseGqlSearchAlbum(data: JsonObject): SpotifyAlbum {
        val uri = data.str("uri") ?: ""
        return SpotifyAlbum(
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            albumType = data.str("type")?.lowercase(),
            artists =
                data.obj("artists")?.arr("items")?.mapNotNull {
                    parseGqlSimpleArtist(it.jsonObject)
                } ?: emptyList(),
            images = parseGqlImages(data.obj("coverArt")?.arr("sources")),
            releaseDate = data.obj("date")?.int("year")?.toString(),
            uri = uri.ifEmpty { null },
        )
    }

    private fun parseGqlSearchArtist(data: JsonObject): SpotifyArtist {
        val uri = data.str("uri") ?: ""
        return SpotifyArtist(
            id = uri.substringAfterLast(":"),
            name = data.obj("profile")?.str("name") ?: "",
            images = parseGqlImages(data.obj("visuals")?.obj("avatarImage")?.arr("sources")),
            uri = uri.ifEmpty { null },
        )
    }

    private fun parseGqlSearchPlaylist(data: JsonObject): SpotifyPlaylist {
        val uri = data.str("uri") ?: ""
        val ownerData = data.obj("ownerV2")?.obj("data")
        val ownerUri = ownerData?.str("uri") ?: ""

        return SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            description = data.str("description"),
            images = parseGqlPlaylistImages(data.obj("images")),
            owner =
                SpotifyPlaylistOwner(
                    id = ownerUri.substringAfterLast(":"),
                    displayName = ownerData?.str("name"),
                    uri = ownerUri.ifEmpty { null },
                ),
            uri = uri.ifEmpty { null },
        )
    }

    // ── Browse: New Releases (GQL: queryWhatsNewFeed) ───────────────────

    suspend fun newReleases(
        limit: Int = 20,
        offset: Int = 0,
    ): Result<NewReleasesResponse> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                    put("onlyUnPlayedItems", false)
                    putJsonArray("includedContentTypes") { add("ALBUM") }
                }

            val response =
                graphqlPost(
                    operationName = "queryWhatsNewFeed",
                    variables = vars,
                )

            val feedData =
                response.obj("data")?.obj("whatsNewFeedItems")
                    ?: throw SpotifyException(500, "Invalid queryWhatsNewFeed response")

            val pagingInfo = feedData.obj("pagingInfo")

            val albums =
                feedData.arr("items")?.mapNotNull { elem ->
                    val content = elem.jsonObject.obj("content") ?: return@mapNotNull null
                    if (content.str("__typename") != "AlbumResponseWrapper") return@mapNotNull null
                    val data = content.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Album") return@mapNotNull null

                    val uri = data.str("uri") ?: return@mapNotNull null
                    SpotifyAlbum(
                        id = uri.substringAfterLast(":"),
                        name = data.str("name") ?: "",
                        albumType = data.str("albumType")?.lowercase(),
                        artists =
                            data.obj("artists")?.arr("items")?.mapNotNull {
                                parseGqlSimpleArtist(it.jsonObject)
                            } ?: emptyList(),
                        images = parseGqlImages(data.obj("coverArt")?.arr("sources")),
                        releaseDate = data.obj("date")?.str("isoString"),
                        uri = uri,
                    )
                } ?: emptyList()

            NewReleasesResponse(
                albums =
                    SpotifyPaging(
                        items = albums,
                        total = feedData.int("totalCount") ?: 0,
                        limit = pagingInfo?.int("limit") ?: limit,
                        offset = pagingInfo?.int("offset") ?: offset,
                    ),
            )
        }

    // ── Home feed (GQL: home) ──────────────────────────────────────────
    //
    // Returns the fully personalized Spotify home: Daily Mix, Discover Weekly,
    // Release Radar, "Jump back in", "More like <artist>", daylist, etc.
    // Shape matches open.spotify.com landing page, one request for ~21 sections.

    suspend fun home(
        sectionItemsLimit: Int = 10,
        timeZone: String = java.util.TimeZone.getDefault().id,
    ): Result<com.metrolist.spotify.models.SpotifyHomeFeed> =
        runCatching {
            log("D", "spotifyHome: GQL home() request — timeZone=$timeZone limit=$sectionItemsLimit")
            val vars =
                buildJsonObject {
                    put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
                    put("timeZone", timeZone)
                    put("sp_t", "")
                    put("facet", "")
                    put("sectionItemsLimit", sectionItemsLimit)
                    put("includeEpisodeContentRatingsV2", false)
                }

            val response =
                graphqlPost(
                    operationName = "home",
                    variables = vars,
                )

            val homeData =
                response.obj("data")?.obj("home")
                    ?: run {
                        log("E", "spotifyHome: GQL response has no data.home — keys=${response.obj("data")?.keys}")
                        throw SpotifyException(500, "Invalid home response")
                    }

            val greeting = homeData.obj("greeting")?.str("transformedLabel")
            log("D", "spotifyHome: GQL home() OK greeting='$greeting'")

            val sectionElements =
                homeData.obj("sectionContainer")
                    ?.obj("sections")
                    ?.arr("items")
                    ?: run {
                        log("W", "spotifyHome: no sectionContainer.sections.items in response")
                        return@runCatching com.metrolist.spotify.models.SpotifyHomeFeed(
                            greeting = greeting,
                            sections = emptyList(),
                        )
                    }

            log("D", "spotifyHome: parsing ${sectionElements.size} raw sections")
            val sections =
                sectionElements.mapNotNull { elem ->
                    parseHomeSection(elem.jsonObject)
                }
            log("D", "spotifyHome: parsed ${sections.size}/${sectionElements.size} sections successfully")

            com.metrolist.spotify.models.SpotifyHomeFeed(
                greeting = greeting,
                sections = sections,
            )
        }

    private fun parseHomeSection(sectionObj: JsonObject): com.metrolist.spotify.models.SpotifyHomeFeedSection? {
        val sectionData = sectionObj.obj("data") ?: return null
        val typename = sectionData.str("__typename") ?: return null
        val titleObj = sectionData.obj("title")
        val title = titleObj?.str("transformedLabel")
            ?: titleObj?.str("translatedBaseText")
            ?: titleObj?.str("text")

        val sectionItems = sectionObj.obj("sectionItems")
        val totalCount = sectionItems?.int("totalCount") ?: 0
        val itemElements = sectionItems?.arr("items") ?: return null

        val items =
            itemElements.mapNotNull { itemElem ->
                parseHomeItem(itemElem.jsonObject)
            }

        if (items.isEmpty()) return null

        return com.metrolist.spotify.models.SpotifyHomeFeedSection(
            sectionUri = sectionObj.str("uri") ?: "",
            title = title,
            typename = typename,
            totalCount = totalCount,
            items = items,
        )
    }

    private fun parseHomeItem(itemObj: JsonObject): com.metrolist.spotify.models.SpotifyHomeFeedItem? {
        val content = itemObj.obj("content") ?: return null
        val wrapper = content.str("__typename") ?: return null
        val data = content.obj("data") ?: return null

        return when (wrapper) {
            "PlaylistResponseWrapper" -> parseHomePlaylist(data)
            "AlbumResponseWrapper" -> parseHomeAlbum(data)
            "ArtistResponseWrapper" -> parseHomeArtist(data)
            else -> null
        }
    }

    private fun parseHomePlaylist(data: JsonObject): com.metrolist.spotify.models.SpotifyHomeFeedItem.Playlist? {
        val uri = data.str("uri") ?: return null
        val imageItem = data.obj("images")?.arr("items")?.firstOrNull()?.jsonObject
        val imageUrl = imageItem?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
        val colorHex = imageItem?.obj("extractedColors")?.obj("colorDark")?.str("hex")
        val madeFor =
            data.arr("attributes")
                ?.firstOrNull { it.jsonObject.str("key") == "madeFor.username" }
                ?.jsonObject?.str("value")

        return com.metrolist.spotify.models.SpotifyHomeFeedItem.Playlist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            description = data.str("description"),
            format = data.str("format"),
            totalCount = data.obj("content")?.int("totalCount") ?: 0,
            imageUrl = imageUrl,
            extractedColorHex = colorHex,
            ownerName = data.obj("ownerV2")?.obj("data")?.str("name"),
            madeForUsername = madeFor,
        )
    }

    private fun parseHomeAlbum(data: JsonObject): com.metrolist.spotify.models.SpotifyHomeFeedItem.Album? {
        val uri = data.str("uri") ?: return null
        val artists =
            data.obj("artists")?.arr("items")?.mapNotNull {
                parseGqlSimpleArtist(it.jsonObject)
            } ?: emptyList()
        val imageUrl =
            data.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")

        return com.metrolist.spotify.models.SpotifyHomeFeedItem.Album(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            albumType = data.str("type")?.lowercase(),
            artists = artists,
            imageUrl = imageUrl,
        )
    }

    private fun parseHomeArtist(data: JsonObject): com.metrolist.spotify.models.SpotifyHomeFeedItem.Artist? {
        val uri = data.str("uri") ?: return null
        val profile = data.obj("profile")
        val imageUrl =
            data.obj("visuals")?.obj("avatarImage")
                ?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
        return com.metrolist.spotify.models.SpotifyHomeFeedItem.Artist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = profile?.str("name") ?: "",
            imageUrl = imageUrl,
        )
    }

    // ── Albums (GQL: getAlbum) ──────────────────────────────────────────

    suspend fun album(albumId: String): Result<SpotifyAlbum> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:album:$albumId")
                    put("locale", "")
                    put("offset", 0)
                    put("limit", 50)
                }

            val response =
                graphqlPost(
                    operationName = "getAlbum",
                    variables = vars,
                )

            val albumData =
                response.obj("data")?.obj("albumUnion")
                    ?: throw SpotifyException(500, "Invalid getAlbum response")

            val artists =
                albumData.obj("artists")?.arr("items")?.mapNotNull {
                    parseGqlSimpleArtist(it.jsonObject)
                } ?: emptyList()

            val albumImages = parseGqlImages(albumData.obj("coverArt")?.arr("sources"))

            val albumSimple =
                SpotifySimpleAlbum(
                    id = albumId,
                    name = albumData.str("name") ?: "",
                    images = albumImages,
                    releaseDate = albumData.obj("date")?.str("isoString"),
                    albumType = albumData.str("type")?.lowercase(),
                    artists = artists,
                    uri = "spotify:album:$albumId",
                )

            val tracksData = albumData.obj("tracksV2")
            val trackItems =
                tracksData?.arr("items")?.mapNotNull { elem ->
                    val trackObj = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    parseGqlTrack(trackObj, albumOverride = albumSimple)
                } ?: emptyList()

            SpotifyAlbum(
                id = albumId,
                name = albumData.str("name") ?: "",
                albumType = albumData.str("type")?.lowercase(),
                artists = artists,
                images = albumImages,
                releaseDate = albumData.obj("date")?.str("isoString"),
                totalTracks = tracksData?.int("totalCount") ?: 0,
                tracks = SpotifyPaging(items = trackItems, total = tracksData?.int("totalCount") ?: 0),
                uri = "spotify:album:$albumId",
            )
        }

    // ── Artists (GQL: queryArtistOverview) ───────────────────────────────

    suspend fun artist(artistId: String): Result<SpotifyArtist> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            SpotifyArtist(
                id = artistId,
                name = artistData.obj("profile")?.str("name") ?: "",
                images = parseGqlImages(artistData.obj("visuals")?.obj("avatarImage")?.arr("sources")),
                uri = "spotify:artist:$artistId",
            )
        }

    suspend fun artistTopTracks(
        artistId: String,
        market: String = "US",
    ): Result<ArtistTopTracksResponse> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            val topTracksItems =
                artistData.obj("discography")
                    ?.obj("topTracks")?.arr("items") ?: JsonArray(emptyList())

            val tracks =
                topTracksItems.mapNotNull { elem ->
                    val trackObj = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    parseGqlTrack(trackObj)
                }

            ArtistTopTracksResponse(tracks = tracks)
        }

    /**
     * Extracts related artists from the GQL queryArtistOverview endpoint.
     * This avoids the rate-limited REST /related-artists endpoint entirely.
     */
    suspend fun artistRelatedArtists(artistId: String): Result<List<SpotifyArtist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            val relatedItems =
                artistData.obj("relatedContent")
                    ?.obj("relatedArtists")
                    ?.arr("items")
                    ?: JsonArray(emptyList())

            relatedItems.mapNotNull { elem ->
                val uri = elem.jsonObject.str("uri") ?: return@mapNotNull null
                val id = uri.substringAfterLast(":")
                val name = elem.jsonObject.obj("profile")?.str("name") ?: return@mapNotNull null
                val images = parseGqlImages(
                    elem.jsonObject.obj("visuals")?.obj("avatarImage")?.arr("sources")
                )
                SpotifyArtist(id = id, name = name, images = images, uri = uri)
            }
        }

    /**
     * Full artist overview in a single GQL round-trip: profile (name, verified,
     * biography), stats (monthly listeners), header/avatar visuals, popular
     * tracks (with play counts), popular releases (discography) and related
     * artists. Every field degrades gracefully to null/empty when the GQL
     * payload doesn't contain it, so a partial response still renders.
     */
    suspend fun artistOverview(artistId: String): Result<SpotifyArtistOverview> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            val profile = artistData.obj("profile")
            val stats = artistData.obj("stats")
            val visuals = artistData.obj("visuals")

            // Monthly listeners can arrive as a number or a string — read content.
            val monthlyListeners =
                stats?.get("monthlyListeners")?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.contentOrNull?.toLongOrNull()

            val biography =
                profile?.obj("biography")?.str("text")
                    ?: profile?.str("biography")

            val avatar = parseGqlImages(visuals?.obj("avatarImage")?.arr("sources"))
            val header = parseGqlImages(visuals?.obj("headerImage")?.arr("sources"))

            val discography = artistData.obj("discography")

            // Popular tracks with play counts.
            val topTrackItems =
                discography?.obj("topTracks")?.arr("items") ?: JsonArray(emptyList())
            val topTracks =
                topTrackItems.mapNotNull { elem ->
                    val trackObj = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    val plays =
                        trackObj["playcount"]?.takeIf { it !is JsonNull }
                            ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ArtistOverviewTrack(track = parseGqlTrack(trackObj), playcount = plays)
                }

            // Full discography. Spotify groups releases under several nodes —
            // a curated `popularReleases` plus `albums`, `singles` and
            // `compilations`. Gather them all so the artist page can show the
            // complete discography (deduped by id, newest first), not just the
            // handful Spotify highlights.
            fun parseRelease(
                elem: kotlinx.serialization.json.JsonElement,
                forcedType: String?,
            ): SpotifyAlbum? {
                // Some shapes nest the actual release under `releases.items[0]`.
                val rel = elem.jsonObject.obj("releases")?.arr("items")
                    ?.firstOrNull()?.jsonObject
                    ?: elem.jsonObject
                val uri = rel.str("uri")
                val id = uri?.substringAfterLast(":") ?: rel.str("id") ?: return null
                return SpotifyAlbum(
                    id = id,
                    name = rel.str("name") ?: "",
                    // Spotify's own discography grouping (albums/singles/compilations
                    // node) is authoritative — the item-level `type` field is often
                    // missing or wrong, which made album/EP labels look random.
                    albumType = forcedType ?: rel.str("type")?.lowercase(),
                    images = parseGqlImages(rel.obj("coverArt")?.arr("sources")),
                    releaseDate = rel.obj("date")?.int("year")?.toString(),
                    uri = uri,
                )
            }
            // Parse the typed nodes FIRST so dedupe keeps their (correct) type over
            // whatever the curated popularReleases node claims.
            val releaseNodes = listOf(
                "albums" to "album",
                "singles" to "single",
                "compilations" to "compilation",
                "popularReleases" to null,
            )
            val popularReleases =
                releaseNodes
                    .flatMap { (node, forcedType) ->
                        discography?.obj(node)?.arr("items").orEmpty()
                            .mapNotNull { parseRelease(it, forcedType) }
                    }
                    .distinctBy { it.id }
                    .sortedByDescending { it.releaseDate?.toIntOrNull() ?: 0 }

            // Albums/compilations this artist appears on (features). Same nested
            // `releases.items[0]` shape as popularReleases; degrade to empty.
            val appearsOnItems =
                artistData.obj("relatedContent")?.obj("appearsOn")?.arr("items")
                    ?: JsonArray(emptyList())
            val appearsOn =
                appearsOnItems.mapNotNull { elem ->
                    val rel = elem.jsonObject.obj("releases")?.arr("items")
                        ?.firstOrNull()?.jsonObject
                        ?: elem.jsonObject
                    val uri = rel.str("uri")
                    val id = uri?.substringAfterLast(":") ?: rel.str("id") ?: return@mapNotNull null
                    SpotifyAlbum(
                        id = id,
                        name = rel.str("name") ?: "",
                        albumType = rel.str("type")?.lowercase(),
                        images = parseGqlImages(rel.obj("coverArt")?.arr("sources")),
                        releaseDate = rel.obj("date")?.int("year")?.toString(),
                        uri = uri,
                    )
                }

            val relatedItems =
                artistData.obj("relatedContent")
                    ?.obj("relatedArtists")
                    ?.arr("items")
                    ?: JsonArray(emptyList())
            val related =
                relatedItems.mapNotNull { elem ->
                    val uri = elem.jsonObject.str("uri") ?: return@mapNotNull null
                    val name = elem.jsonObject.obj("profile")?.str("name") ?: return@mapNotNull null
                    SpotifyArtist(
                        id = uri.substringAfterLast(":"),
                        name = name,
                        images = parseGqlImages(
                            elem.jsonObject.obj("visuals")?.obj("avatarImage")?.arr("sources")
                        ),
                        uri = uri,
                    )
                }

            SpotifyArtistOverview(
                id = artistId,
                name = profile?.str("name") ?: "",
                verified = profile?.get("verified")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                monthlyListeners = monthlyListeners,
                biography = biography,
                avatarImages = avatar,
                headerImages = header,
                topTracks = topTracks,
                popularReleases = popularReleases,
                appearsOn = appearsOn,
                relatedArtists = related,
            )
        }

    // ── Related Artists (REST fallback) ─────────────────────────────────

    suspend fun relatedArtists(artistId: String): Result<RelatedArtistsResponse> =
        runCatching {
            authenticatedGet("artists/$artistId/related-artists")
        }

    fun isAuthenticated(): Boolean = accessToken != null

    /** The looping Canvas video URL for a track, or null when it has none. */
    suspend fun canvasUrl(trackId: String): String? {
        val token = accessToken ?: return null
        return SpotifyCanvas.canvasUrl(trackId, token)
    }
}

@kotlinx.serialization.Serializable
data class ArtistTopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class SpotifyArtistOverview(
    val id: String = "",
    val name: String = "",
    val verified: Boolean = false,
    val monthlyListeners: Long? = null,
    val biography: String? = null,
    val avatarImages: List<com.metrolist.spotify.models.SpotifyImage> = emptyList(),
    val headerImages: List<com.metrolist.spotify.models.SpotifyImage> = emptyList(),
    val topTracks: List<ArtistOverviewTrack> = emptyList(),
    val popularReleases: List<com.metrolist.spotify.models.SpotifyAlbum> = emptyList(),
    val appearsOn: List<com.metrolist.spotify.models.SpotifyAlbum> = emptyList(),
    val relatedArtists: List<SpotifyArtist> = emptyList(),
)

@kotlinx.serialization.Serializable
data class ArtistOverviewTrack(
    val track: SpotifyTrack,
    val playcount: Long? = null,
)

@kotlinx.serialization.Serializable
data class RelatedArtistsResponse(
    val artists: List<SpotifyArtist> = emptyList(),
)

@kotlinx.serialization.Serializable
data class NewReleasesResponse(
    val albums: SpotifyPaging<SpotifyAlbum>? = null,
)
