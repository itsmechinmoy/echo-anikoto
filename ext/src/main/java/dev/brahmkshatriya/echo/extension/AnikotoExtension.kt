package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackChapterClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Chapter
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.AnikotoUtils.toEchoDate
import dev.brahmkshatriya.echo.extension.AnikotoUtils.toProxiedImageHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AnikotoExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    TrackChapterClient,
    LyricsClient,
    ShareClient {

    private var setting: Settings? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val extractor by lazy { AnikotoExtractor(client, json) }

    private val aniZipCache = MemoryCache<String, AniZipResponseDto>(ttlMs = 30 * 60 * 1000L)
    private val aniSkipCache = MemoryCache<String, AniSkipResponseDto>(ttlMs = 30 * 60 * 1000L)
    private val detailsCache = MemoryCache<String, Album>(ttlMs = 15 * 60 * 1000L)
    private val episodeListCache = MemoryCache<String, List<Track>>(ttlMs = 15 * 60 * 1000L)

    private val baseUrl: String
        get() = setting?.getString(PREF_DOMAIN_KEY) ?: DOMAINS.first()

    override suspend fun onInitialize() {}

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        return mutableListOf(
            SettingList(
                key = PREF_DOMAIN_KEY,
                title = "Preferred Domain",
                summary = "Active mirror domain for Anikoto",
                entryTitles = DOMAINS.toMutableList(),
                entryValues = DOMAINS.toMutableList(),
                defaultEntryIndex = 0,
            ),
            SettingList(
                key = PREF_QUALITY_KEY,
                title = "Preferred Quality",
                summary = "Preferred video playback quality",
                entryTitles = mutableListOf("1080p", "720p", "480p", "360p", "Auto"),
                entryValues = mutableListOf("1080p", "720p", "480p", "360p", "Auto"),
                defaultEntryIndex = 0,
            ),
            SettingList(
                key = PREF_SERVER_KEY,
                title = "Preferred Server",
                summary = "Preferred video streaming server",
                entryTitles = mutableListOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"),
                entryValues = mutableListOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"),
                defaultEntryIndex = 0,
            ),
            SettingList(
                key = PREF_LANG_KEY,
                title = "Preferred Audio/Sub",
                summary = "Preferred audio language (Sub / Dub)",
                entryTitles = mutableListOf("Sub (Japanese)", "Dub (English)", "Show All"),
                entryValues = mutableListOf("Sub", "Dub", "All"),
                defaultEntryIndex = 0,
            ),
            SettingSwitch(
                key = PREF_FILLER_TAG_KEY,
                title = "Show Filler Tag in Episode Titles",
                summary = "Adds '(Filler)' to episode names when available.",
                defaultValue = PREF_FILLER_TAG_DEFAULT,
            ),
            SettingSwitch(
                key = PREF_FILLER_HIDE_KEY,
                title = "Hide Filler Episodes",
                summary = "Hides detected filler episodes from episode list.",
                defaultValue = PREF_FILLER_HIDE_DEFAULT,
            ),
            SettingSwitch(
                key = PREF_AUTOSKIP_KEY,
                title = "Auto-Skip OP/ED Segments",
                summary = "Automatically skip Opening and Ending segments without asking.",
                defaultValue = PREF_AUTOSKIP_DEFAULT,
            ),
        )
    }

    // =========================== Home Feed ===========================

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = mutableListOf(
            Tab("most-viewed", "Most Viewed", false),
            Tab("latest-updated", "Latest Updates", false),
            Tab("new-release", "New Releases", false),
            Tab("status/currently-airing", "Currently Airing", false),
            Tab("status/finished-airing", "Finished Airing", false),
            Tab("status/not-yet-aired", "Upcoming", false),
        )

        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                val path = tab?.id ?: "most-viewed"
                val url = "$baseUrl/$path?page=$page"
                val html = httpGet(url)
                val (albums, hasNext) = parseAnimeListing(html)

                val shelves = albums.map { it.toShelf() }
                val nextPage = if (hasNext) (page + 1).toString() else null
                Page(shelves, nextPage)
            }
            pagedData.toFeedData()
        }
    }

    // =========================== Search Feed ===========================

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val pagedData = PagedData.Continuous<Shelf> { continuation ->
            val page = continuation?.toIntOrNull() ?: 1
            val vrf = if (query.isNotBlank()) AnikotoUtils.vrfEncrypt(query) else ""
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/filter?keyword=$encodedQ&page=$page&vrf=$vrf"

            val html = httpGet(url)
            val (albums, hasNext) = parseAnimeListing(html)
            val shelves = albums.map { it.toShelf() }
            val nextPage = if (hasNext) (page + 1).toString() else null
            Page(shelves, nextPage)
        }
        return pagedData.toFeed()
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val vrf = AnikotoUtils.vrfEncrypt(query)
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/filter?keyword=$encodedQ&page=1&vrf=$vrf"
            val html = httpGet(url)
            val (albums, _) = parseAnimeListing(html)
            albums.take(8).map { QuickSearchItem.Media(it, false) }
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}

    // =========================== Album Client ===========================

    override suspend fun loadAlbum(album: Album): Album = withContext(Dispatchers.IO) {
        val cached = detailsCache.get(album.id)
        if (cached != null) return@withContext cached

        val url = if (album.id.startsWith("http")) album.id else "$baseUrl${album.id}"
        val html = httpGet(url)
        val doc = Jsoup.parse(html, baseUrl)

        val titleElement = doc.selectFirst("h1.title, h2.title")
        val title = titleElement?.text()?.ifBlank { null } ?: album.title
        val animeId = doc.selectFirst("[data-id]")?.attr("data-id")
            ?: doc.selectFirst("[data-tip]")?.attr("data-tip")
            ?: album.extras["animeId"]

        val posterUrl = doc.selectFirst("div.poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val cover = toProxiedImageHolder(posterUrl, baseUrl) ?: album.cover

        val genres = doc.select("div:contains(Genres) > span > a").map { it.text().trim() }
        val studios = doc.select("div:contains(Studios) > span > a").map { it.text().trim() }
        val synopsis = doc.selectFirst("div.synopsis > div.shorting > div.content, div.synopsis div.content")?.text()

        val artists = studios.map { Artist(id = it, name = it, cover = null) }

        val malId = doc.selectFirst("a[href*='myanimelist.net/anime/']")?.attr("href")
            ?.let { Regex("""/anime/(\d+)""").find(it)?.groupValues?.get(1) }
            ?: album.extras["mal_id"]

        val loadedAlbum = album.copy(
            title = title,
            type = Album.Type.Show,
            cover = cover,
            artists = artists,
            description = synopsis,
            extras = album.extras + buildMap {
                animeId?.let { put("animeId", it) }
                malId?.let { put("mal_id", it) }
            }
        )

        detailsCache.put(album.id, loadedAlbum)
        loadedAlbum
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = withContext(Dispatchers.IO) {
        val animeId = album.extras["animeId"] ?: return@withContext null
        val currentPath = album.id.substringBefore("?").substringBefore("#")
        val shelves = mutableListOf<Shelf>()

        // 1. Watch Order / Franchise Shelf
        try {
            val listHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Referer" to "$baseUrl$currentPath",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT
            )
            val req = Request.Builder().url("$baseUrl/api/watch-order/$animeId")
            listHeaders.forEach { (k, v) -> req.header(k, v) }
            val responseBody = client.newCall(req.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }

            if (responseBody != null) {
                val relatedDoc = json.decodeFromString<ResultResponseDto>(responseBody).toDocument()
                val seasonAlbums = relatedDoc.select("div.item.flexserieslist").mapNotNull { el ->
                    val href = el.selectFirst("a[href*=/watch/]")?.attr("href")
                        ?: el.attr("href").takeIf { it.contains("/watch/") } ?: return@mapNotNull null
                    val cleanPath = href.substringBefore("?").trim()
                    if (cleanPath == currentPath) return@mapNotNull null

                    val name = el.selectFirst(".info .name")?.text()?.trim() ?: return@mapNotNull null
                    val imgUrl = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    val cover = toProxiedImageHolder(imgUrl, baseUrl)
                    val relation = el.selectFirst(".badge, .type")?.text()?.trim()
                        ?: AnikotoUtils.detectRelationFromTitle(name)

                    Album(
                        id = cleanPath,
                        title = name,
                        subtitle = relation,
                        type = Album.Type.Show,
                        cover = cover,
                    )
                }

                if (seasonAlbums.isNotEmpty()) {
                    shelves.add(
                        Shelf.Lists.Items(
                            id = "watch-order",
                            title = "Watch Order & Seasons",
                            list = seasonAlbums.distinctBy { it.id }
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Recommended Shelf
        try {
            val fullUrl = if (currentPath.startsWith("http")) currentPath else "$baseUrl$currentPath"
            val html = httpGet(fullUrl)
            val doc = Jsoup.parse(html, baseUrl)
            val recSection = doc.select("section.w-side-section").firstOrNull {
                it.select(".head .title").text().contains("Recommended", ignoreCase = true)
            }
            val recAlbums = recSection?.select("a.item")?.mapNotNull { el ->
                val href = el.attr("href").substringBefore("?").trim()
                if (href.isEmpty() || href == currentPath) return@mapNotNull null
                val name = el.selectFirst(".info .name")?.text()?.trim() ?: return@mapNotNull null
                val imgUrl = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                val cover = toProxiedImageHolder(imgUrl, baseUrl)

                Album(
                    id = href,
                    title = name,
                    subtitle = "Recommended",
                    type = Album.Type.Show,
                    cover = cover,
                )
            }.orEmpty()

            if (recAlbums.isNotEmpty()) {
                shelves.add(
                    Shelf.Lists.Items(
                        id = "recommended",
                        title = "Recommended Anime",
                        list = recAlbums.distinctBy { it.id }
                    )
                )
            }
        } catch (_: Exception) {}

        if (shelves.isEmpty()) null else shelves.toFeed()
    }

    // =========================== Track Client ===========================

    override suspend fun loadTracks(album: Album): Feed<Track>? = withContext(Dispatchers.IO) {
        val cached = episodeListCache.get(album.id)
        if (cached != null) return@withContext cached.toFeed()

        val animeId = album.extras["animeId"] ?: run {
            val loaded = loadAlbum(album)
            loaded.extras["animeId"] ?: return@withContext null
        }

        val animeUrl = album.id.substringBefore("?").substringBefore("#")
        val vrf = AnikotoUtils.vrfEncrypt(animeId)
        val listHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$baseUrl$animeUrl",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to USER_AGENT
        )

        val req = Request.Builder().url("$baseUrl/ajax/episode/list/$animeId?vrf=$vrf")
        listHeaders.forEach { (k, v) -> req.header(k, v) }

        val responseBody = client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return@withContext null

        val doc = json.decodeFromString<ResultResponseDto>(responseBody).toDocument()
        val elements = doc.select("div.episodes ul > li > a")

        val malIdFromEp = elements.firstOrNull { it.hasAttr("data-mal") }?.attr("data-mal")?.takeIf { it.isNotBlank() }
        val effectiveMalId = malIdFromEp ?: album.extras["mal_id"]

        val aniZipMeta = effectiveMalId?.let { fetchAniZip(it) }

        val hideFiller = setting?.getBoolean(PREF_FILLER_HIDE_KEY) ?: PREF_FILLER_HIDE_DEFAULT
        val showFillerTag = setting?.getBoolean(PREF_FILLER_TAG_KEY) ?: PREF_FILLER_TAG_DEFAULT

        val tracks = elements
            .filter { el -> !hideFiller || !el.hasClass("filler") }
            .mapNotNull { el ->
                val epNum = el.attr("data-num")
                val ids = el.attr("data-ids")
                if (ids.isBlank()) return@mapNotNull null

                val tooltip = el.parent()?.attr("title") ?: ""
                val isFiller = el.hasClass("filler")
                val fillerTag = if (isFiller && showFillerTag) " (Filler)" else ""

                val aniZipEp = aniZipMeta?.episodes?.get(epNum)
                val aniZipTitle = aniZipEp?.getEnglishOrRomajiTitle()

                var title = el.parent()?.select("span.d-title")?.text().orEmpty()
                if (title.isBlank() && tooltip.isNotEmpty()) {
                    title = tooltip.substringBefore("Release:").substringBefore("Softsub").trim()
                }

                val cleanNum = epNum.removeSuffix(".0")
                val fullTitle = when {
                    !aniZipTitle.isNullOrBlank() -> "$cleanNum. $aniZipTitle$fillerTag"
                    title.isNotBlank() && !title.equals("Episode $epNum", ignoreCase = true) && title != epNum -> "$cleanNum. $title$fillerTag"
                    else -> "Episode $cleanNum$fillerTag"
                }

                val epCover = toProxiedImageHolder(aniZipEp?.image, baseUrl) ?: album.cover
                val epDescription = aniZipEp?.overview ?: aniZipEp?.summary
                val epDuration = aniZipEp?.runtime?.let { it * 60 * 1000L } ?: aniZipEp?.length?.let { it * 60 * 1000L }
                val epReleaseDate = (aniZipEp?.airDate ?: aniZipEp?.airdate)?.toEchoDate()

                val mal = el.attr("data-mal").takeIf { it.isNotBlank() } ?: effectiveMalId
                val slug = el.attr("data-slug")
                val ts = el.attr("data-timestamp")

                Track(
                    id = ids,
                    title = fullTitle,
                    type = Track.Type.Video,
                    album = album,
                    cover = epCover,
                    description = epDescription,
                    duration = epDuration,
                    releaseDate = epReleaseDate,
                    artists = album.artists,
                    extras = buildMap {
                        put("ids", ids)
                        put("epNum", epNum)
                        put("animeUrl", animeUrl)
                        mal?.let { put("mal_id", it) }
                        if (slug.isNotEmpty()) put("slug", slug)
                        if (ts.isNotEmpty()) put("ts", ts)
                        if (epDescription != null) put("overview", epDescription)
                    }
                )
            }

        val sortedTracks = tracks.sortedBy { it.extras["epNum"]?.toDoubleOrNull() ?: 0.0 }
        episodeListCache.put(album.id, sortedTracks)
        sortedTracks.toFeed()
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = withContext(Dispatchers.IO) {
        val ids = track.extras["ids"] ?: track.id
        val animeUrl = track.extras["animeUrl"] ?: track.album?.id ?: ""
        val epNum = track.extras["epNum"] ?: "1"
        val epUrl = "$animeUrl/ep-$epNum"

        val malId = track.extras["mal_id"]
        val slug = track.extras["slug"]
        val ts = track.extras["ts"]

        val listHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$baseUrl$epUrl",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to USER_AGENT
        )

        val encodedIds = URLEncoder.encode(ids, "UTF-8")
        val req = Request.Builder().url("$baseUrl/ajax/server/list?servers=$encodedIds")
        listHeaders.forEach { (k, v) -> req.header(k, v) }

        val responseBody = client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }

        val streamables = mutableListOf<Streamable>()

        if (responseBody != null) {
            try {
                val doc = json.decodeFromString<ResultResponseDto>(responseBody).toDocument()
                doc.select("div.servers div.type ul li[data-link-id]").forEach { srv ->
                    val linkId = srv.attr("data-link-id")
                    val serverName = srv.text().trim().ifBlank { srv.attr("data-sv-id") }
                    val typeAttr = srv.parents().firstOrNull { it.hasClass("type") }?.attr("data-type") ?: ""
                    val isDub = typeAttr.equals("dub", ignoreCase = true) || srv.parents().any { it.attr("data-type") == "dub" }
                    val langPrefix = if (isDub) "🇬🇧 $serverName (Dub)" else "🇯🇵 $serverName (Sub)"

                    streamables.add(
                        Streamable.server(
                            id = linkId,
                            quality = if (serverName.contains("HD", true)) 1080 else 720,
                            title = langPrefix,
                            extras = mapOf(
                                "serverId" to linkId,
                                "serverName" to serverName,
                                "epUrl" to epUrl,
                                "isMapper" to "false"
                            )
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        // Add Mapper servers if available
        if (!malId.isNullOrBlank() && !slug.isNullOrBlank() && !ts.isNullOrBlank()) {
            val mapperServers = extractor.fetchMapperServers(MAPPER_URL, baseUrl, malId, slug, ts)
            mapperServers.forEach { (key, dto) ->
                dto.sub?.url?.takeIf { it.isNotBlank() }?.let { link ->
                    streamables.add(
                        Streamable.server(
                            id = link,
                            quality = 1080,
                            title = "🇯🇵 Mapper: $key (H-Sub)",
                            extras = mapOf(
                                "embedUrl" to link,
                                "serverName" to key,
                                "epUrl" to epUrl,
                                "isMapper" to "true"
                            )
                        )
                    )
                }
                dto.dub?.url?.takeIf { it.isNotBlank() }?.let { link ->
                    streamables.add(
                        Streamable.server(
                            id = link,
                            quality = 1080,
                            title = "🇬🇧 Mapper: $key (A-Dub)",
                            extras = mapOf(
                                "embedUrl" to link,
                                "serverName" to key,
                                "epUrl" to epUrl,
                                "isMapper" to "true"
                            )
                        )
                    )
                }
            }
        }

        val sortedStreamables = sortStreamables(streamables)
        val subtitles = mutableListOf<Streamable>()

        // Pre-fetch subtitles from the primary Sub server so player has subtitles ready immediately
        val primarySubServer = sortedStreamables.firstOrNull { it.title?.contains("(Sub)") == true }
            ?: sortedStreamables.firstOrNull { it.extras["isMapper"] != "true" }
            ?: sortedStreamables.firstOrNull()

        if (primarySubServer != null) {
            try {
                val embedUrl = if (primarySubServer.extras["isMapper"] == "true") {
                    primarySubServer.extras["embedUrl"] ?: primarySubServer.id
                } else {
                    extractor.getEmbedData(baseUrl, primarySubServer.id, epUrl)?.url
                }

                if (!embedUrl.isNullOrBlank()) {
                    val extracted = extractor.extract(embedUrl, primarySubServer.title ?: "Server", "$baseUrl/")
                    if (extracted.subtitles.isNotEmpty()) {
                        subtitles.addAll(extracted.subtitles)
                    }
                }
            } catch (_: Exception) {}
        }

        val sortedSubtitles = sortSubtitles(subtitles.distinctBy { it.id })

        track.copy(
            streamables = sortedStreamables + sortedSubtitles,
        )
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = withContext(Dispatchers.IO) {
        if (streamable.type == Streamable.MediaType.Subtitle) {
            val subUrl = streamable.extras["url"] ?: streamable.id
            val subType = when {
                subUrl.endsWith(".srt", ignoreCase = true) -> Streamable.SubtitleType.SRT
                subUrl.endsWith(".ass", ignoreCase = true) || subUrl.endsWith(".ssa", ignoreCase = true) -> Streamable.SubtitleType.ASS
                else -> Streamable.SubtitleType.VTT
            }
            return@withContext Streamable.Media.Subtitle(url = subUrl, type = subType)
        }

        val isMapper = streamable.extras["isMapper"] == "true"
        val serverName = streamable.extras["serverName"] ?: "Server"
        val epUrl = streamable.extras["epUrl"] ?: ""

        val embedUrl = if (isMapper) {
            streamable.extras["embedUrl"] ?: streamable.id
        } else {
            val embedData = extractor.getEmbedData(baseUrl, streamable.id, epUrl)
            embedData?.url ?: throw Exception("Failed to resolve embed stream for server $serverName")
        }

        val extracted = extractor.extract(embedUrl, serverName, "$baseUrl/")
        if (extracted.sources.isEmpty()) {
            throw Exception("No playable video sources found for $serverName")
        }

        Streamable.Media.Server(sources = sortSources(extracted.sources), merged = false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return track.album?.let { loadFeed(it) }
    }

    // =========================== AniSkip Chapters ===========================

    override suspend fun getChapters(track: Track): List<Chapter> = withContext(Dispatchers.IO) {
        val malId = track.extras["mal_id"] ?: return@withContext emptyList()
        val epNumStr = track.extras["epNum"] ?: return@withContext emptyList()
        val epNum = epNumStr.toDoubleOrNull()?.toInt() ?: return@withContext emptyList()

        val autoSkip = setting?.getBoolean(PREF_AUTOSKIP_KEY) ?: PREF_AUTOSKIP_DEFAULT

        val skipData = fetchAniSkip(malId, epNum) ?: return@withContext emptyList()
        if (!skipData.found || skipData.results.isEmpty()) return@withContext emptyList()

        skipData.results.map { res ->
            val name = when (res.skipType.lowercase()) {
                "op" -> "Opening"
                "ed" -> "Ending"
                "mixed-op" -> "Mixed Opening"
                "mixed-ed" -> "Mixed Ending"
                "recap" -> "Recap"
                else -> res.skipType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
            }
            val startMs = (res.interval.startTime * 1000).toLong()
            val endMs = (res.interval.endTime * 1000).toLong()

            Chapter(
                name = name,
                startTime = startMs,
                endTime = endMs,
                skipType = if (autoSkip) Chapter.SkipType.SKIP else Chapter.SkipType.ASK,
            )
        }.sortedBy { it.startTime }
    }

    // =========================== Lyrics Tab ===========================

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Lyrics>()

        val desc = track.description?.takeIf { it.isNotBlank() } ?: track.extras["overview"]
        if (!desc.isNullOrBlank()) {
            list.add(
                Lyrics(
                    id = "synopsis_${track.id}",
                    title = "Episode Synopsis",
                    subtitle = track.title,
                    lyrics = Lyrics.Simple(desc),
                    extras = mapOf("text" to desc)
                )
            )
        }

        val chapters = getChapters(track)
        if (chapters.isNotEmpty()) {
            val timedItems = chapters.map { ch ->
                Lyrics.Item(
                    text = "▶ ${ch.name}",
                    startTime = ch.startTime,
                    endTime = ch.endTime ?: (ch.startTime + 90000L)
                )
            }
            list.add(
                Lyrics(
                    id = "chapters_${track.id}",
                    title = "Episode Segments & OP/ED",
                    subtitle = "AniSkip Markers",
                    lyrics = Lyrics.Timed(timedItems, fillTimeGaps = true),
                    extras = mapOf("isChapters" to "true")
                )
            )
        }

        list.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        if (lyrics.lyrics != null) return lyrics
        val text = lyrics.extras["text"]
        if (!text.isNullOrBlank()) {
            return lyrics.copy(lyrics = Lyrics.Simple(text))
        }
        return lyrics
    }

    // =========================== Share Client ===========================

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Album -> if (item.id.startsWith("http")) item.id else "$baseUrl${item.id}"
            is Track -> {
                val animeUrl = item.extras["animeUrl"] ?: item.album?.id ?: ""
                val epNum = item.extras["epNum"] ?: "1"
                "$baseUrl$animeUrl/ep-$epNum"
            }
            else -> baseUrl
        }
    }

    // =========================== Helpers ===========================

    private fun parseAnimeListing(html: String): Pair<List<Album>, Boolean> {
        val doc = Jsoup.parse(html, baseUrl)
        val albums = doc.select("div.ani.items > div.item").mapNotNull { el ->
            val a = el.selectFirst("a.name") ?: return@mapNotNull null
            val href = a.attr("href").substringBefore("?").replace(Regex("""/ep-\d+$"""), "")
            val title = a.text().trim()
            val posterUrl = el.selectFirst("div.poster img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            val cover = toProxiedImageHolder(posterUrl, baseUrl)

            Album(
                id = href,
                title = title,
                type = Album.Type.Show,
                cover = cover
            )
        }

        val hasNext = doc.select("nav > ul.pagination > li.active ~ li").isNotEmpty()
        return Pair(albums, hasNext)
    }

    private suspend fun fetchAniZip(malId: String): AniZipResponseDto? = withContext(Dispatchers.IO) {
        val cached = aniZipCache.get(malId)
        if (cached != null) return@withContext cached

        try {
            val req = Request.Builder().url("https://api.ani.zip/mappings?mal_id=$malId").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return@withContext null
                    val res = json.decodeFromString<AniZipResponseDto>(body)
                    aniZipCache.put(malId, res)
                    res
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchAniSkip(malId: String, epNum: Int): AniSkipResponseDto? = withContext(Dispatchers.IO) {
        val key = "$malId:$epNum"
        val cached = aniSkipCache.get(key)
        if (cached != null) return@withContext cached

        try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$epNum?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap&episodeLength=0"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return@withContext null
                    val res = json.decodeFromString<AniSkipResponseDto>(body)
                    aniSkipCache.put(key, res)
                    res
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sortStreamables(streamables: List<Streamable>): List<Streamable> {
        val prefLang = setting?.getString(PREF_LANG_KEY) ?: "Sub"
        val prefServer = setting?.getString(PREF_SERVER_KEY) ?: "HD-1"

        return streamables.sortedWith(
            compareByDescending<Streamable> { s ->
                when {
                    prefLang == "Sub" && s.title?.contains("(Sub)") == true -> 2
                    prefLang == "Dub" && s.title?.contains("(Dub)") == true -> 2
                    else -> 1
                }
            }.thenByDescending { s ->
                if (s.title?.contains(prefServer, true) == true) 2 else 0
            }
        )
    }

    private fun sortSources(sources: List<Streamable.Source>): List<Streamable.Source> {
        val prefQuality = setting?.getString(PREF_QUALITY_KEY) ?: PREF_QUALITY_DEFAULT
        val targetHeight = prefQuality.replace("p", "").toIntOrNull() ?: 1080

        return sources.sortedWith(
            compareByDescending<Streamable.Source> { s ->
                if (s.quality == targetHeight) 3
                else if (s.quality > 0) 2
                else 1 // Auto
            }.thenByDescending { it.quality }
        )
    }

    private fun sortSubtitles(subtitles: List<Streamable>): List<Streamable> {
        return subtitles.sortedWith(
            compareByDescending<Streamable> { s ->
                val title = s.title?.lowercase() ?: ""
                when {
                    title.startsWith("english") || title == "en" || title == "eng" -> 3
                    title.contains("english") -> 2
                    else -> 1
                }
            }
        )
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code} for URL: $url")
            }
            response.body?.string() ?: ""
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        private val DOMAINS = listOf(
            "https://anikototv.to",
            "https://anikoto.bz",
            "https://anikoto.cz",
            "https://anikoto.me",
            "https://anikoto.net",
            "https://anikototv.se"
        )

        private const val MAPPER_URL = "https://mapper.nekostream.site/api"

        private const val PREF_DOMAIN_KEY = "preferred_domain"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_LANG_KEY = "preferred_lang"

        private const val PREF_FILLER_TAG_KEY = "append_filler_tag"
        private const val PREF_FILLER_TAG_DEFAULT = true

        private const val PREF_FILLER_HIDE_KEY = "hide_filler"
        private const val PREF_FILLER_HIDE_DEFAULT = false

        private const val PREF_AUTOSKIP_KEY = "auto_skip_op_ed"
        private const val PREF_AUTOSKIP_DEFAULT = false
    }
}
