package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64

data class ExtractedStream(
    val sources: List<Streamable.Source>,
    val subtitles: List<Streamable>,
    val introSkip: Pair<Long, Long>? = null,
    val outroSkip: Pair<Long, Long>? = null,
)

class AnikotoExtractor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun getEmbedData(baseUrl: String, serverId: String, epUrl: String): ServerResultDto? {
        val listHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$baseUrl$epUrl",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to USER_AGENT
        )

        val encodedId = URLEncoder.encode(serverId, "UTF-8")
        val reqBuilder = Request.Builder().url("$baseUrl/ajax/server?get=$encodedId")
        listHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return null
                    json.decodeFromString<ServerResponseDto>(body).result
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchMapperServers(
        mapperUrl: String,
        baseUrl: String,
        malId: String,
        slug: String,
        ts: String
    ): Map<String, MapperServerDto> {
        val apiUrl = "$mapperUrl/mal/$malId/$slug/$ts"
        val mapperHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$baseUrl/",
            "Origin" to baseUrl,
            "User-Agent" to USER_AGENT
        )

        val reqBuilder = Request.Builder().url(apiUrl)
        mapperHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) emptyMap()
                else {
                    val body = response.body?.string() ?: return emptyMap()
                    json.decodeFromString<Map<String, MapperServerDto?>>(body)
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun extract(embedUrl: String, serverName: String, referer: String): ExtractedStream {
        return try {
            when {
                embedUrl.contains("mewcdn.online/player/plyr.php") ->
                    extractFromMewcdn(embedUrl, serverName)
                embedUrl.endsWith(".m3u8") || (embedUrl.contains(".m3u8") && !embedUrl.contains("/stream/")) ->
                    extractDirectM3u8(embedUrl, serverName, referer)
                else ->
                    extractFromPlayer(embedUrl, serverName, referer)
            }
        } catch (_: Exception) {
            ExtractedStream(emptyList(), emptyList())
        }
    }

    private fun extractFromPlayer(embedUrl: String, serverName: String, pageReferer: String): ExtractedStream {
        val host = embedUrl.toHttpUrlOrNull()?.host ?: return ExtractedStream(emptyList(), emptyList())
        val reqBuilder = Request.Builder().url(embedUrl)
            .header("Referer", pageReferer)
            .header("User-Agent", USER_AGENT)

        val pageBody = client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return ExtractedStream(emptyList(), emptyList())
            response.body?.string() ?: ""
        }

        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, serverName)
        }

        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = resolveUrl(iframeSrc, embedUrl)
            return extractFromPlayer(resolvedSrc, serverName, embedUrl)
        }

        val directM3u8 = M3U8_REGEX.find(pageBody)?.groupValues?.get(0)
        if (directM3u8 != null) {
            return extractDirectM3u8(directM3u8, serverName, "https://$host/")
        }

        val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            val resolvedSrc = resolveUrl(sourceSrc, embedUrl)
            return extractDirectM3u8(resolvedSrc, serverName, "https://$host/")
        }

        val jsVarUrl = JS_VAR_M3U8_REGEX.find(pageBody)?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)
        }
        if (jsVarUrl != null) {
            val resolvedUrl = resolveUrl(jsVarUrl, embedUrl)
            return extractDirectM3u8(resolvedUrl, serverName, "https://$host/")
        }

        return ExtractedStream(emptyList(), emptyList())
    }

    private fun fetchSourcesFromApi(dataId: String, host: String, embedUrl: String, serverName: String): ExtractedStream {
        val streamType = embedUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.takeIf { it == "sub" || it == "dub" || it == "hsub" } ?: ""

        val apiHeaders = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to embedUrl,
            "Origin" to "https://$host",
            "User-Agent" to USER_AGENT
        )

        val sourceData = fetchSourceDto(dataId, host, apiHeaders, streamType) ?: return ExtractedStream(emptyList(), emptyList())
        val m3u8Url = sourceData.sources.takeIf { it.startsWith("http") } ?: return ExtractedStream(emptyList(), emptyList())

        val subtitles = sourceData.tracks
            ?.filter { it.kind == "captions" || it.kind == "subtitles" }
            ?.map { track ->
                Streamable.subtitle(
                    id = track.file,
                    title = track.label.ifBlank { "Subtitle" },
                    extras = mapOf("url" to track.file)
                )
            }.orEmpty()

        val vidHeaders = mapOf(
            "Referer" to "https://$host/",
            "Origin" to "https://$host",
            "User-Agent" to USER_AGENT
        )

        val sources = AnikotoUtils.extractHlsSources(m3u8Url, client, vidHeaders, serverName)
        return ExtractedStream(sources, subtitles)
    }

    private fun fetchSourceDto(dataId: String, host: String, headers: Map<String, String>, streamType: String): SourceResponseDto? {
        val primaryUrl = "https://$host/stream/getSources?id=$dataId&id=$dataId&type=$streamType&type=$streamType"
        val req1 = Request.Builder().url(primaryUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            client.newCall(req1).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) return json.decodeFromString<SourceResponseDto>(body)
                }
            }
        } catch (_: Exception) {}

        val newUrl = "https://$host/stream/getSourcesNew?id=$dataId&id=$dataId&type=$streamType&type=$streamType"
        val req2 = Request.Builder().url(newUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            client.newCall(req2).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) return json.decodeFromString<SourceResponseDto>(body)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun extractDirectM3u8(m3u8Url: String, serverName: String, referer: String): ExtractedStream {
        val vidHeaders = mapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT
        )
        val sources = AnikotoUtils.extractHlsSources(m3u8Url, client, vidHeaders, serverName)
        return ExtractedStream(sources, emptyList())
    }

    private fun extractFromMewcdn(embedUrl: String, serverName: String): ExtractedStream {
        val fragment = embedUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotEmpty() }
            ?: return ExtractedStream(emptyList(), emptyList())

        val rawM3u8 = try {
            String(Base64.getDecoder().decode(fragment), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return ExtractedStream(emptyList(), emptyList())
        }
        if (!rawM3u8.startsWith("http")) return ExtractedStream(emptyList(), emptyList())

        val pageHeaders = mapOf(
            "Referer" to embedUrl,
            "User-Agent" to USER_AGENT
        )
        val req = Request.Builder().url(embedUrl).apply { pageHeaders.forEach { (k, v) -> header(k, v) } }.build()
        val hostMap = try {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) emptyMap()
                else parseHostMap(response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            emptyMap()
        }

        val m3u8 = applyHostMap(rawM3u8, hostMap)
        val vidHeaders = mapOf(
            "Referer" to "https://mewcdn.online/",
            "Origin" to "https://mewcdn.online",
            "User-Agent" to USER_AGENT
        )
        val sources = AnikotoUtils.extractHlsSources(m3u8, client, vidHeaders, serverName)
        return ExtractedStream(sources, emptyList())
    }

    private fun parseHostMap(html: String): Map<String, String> {
        val mapMatch = HOST_MAP_REGEX.find(html) ?: return emptyMap()
        return HOST_ENTRY_REGEX.findAll(mapMatch.groupValues[1]).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun applyHostMap(url: String, hostMap: Map<String, String>): String {
        var result = url
        for ((origin, proxy) in hostMap) {
            if (result.contains(origin)) {
                result = result.replace(origin, proxy)
                break
            }
        }
        return result
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        val baseUrl = base.toHttpUrlOrNull() ?: return url
        return baseUrl.resolve(url)?.toString() ?: url
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
        private val JS_VAR_M3U8_REGEX = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']"""
        )
        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}
