package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
                isMegaPlayServer(serverName) || isMegaPlayUrl(embedUrl) ->
                    extractFromMegaPlay(embedUrl, serverName)
                embedUrl.contains("mewcdn.online/player/plyr.php") ->
                    extractFromMewcdn(embedUrl, serverName)
                embedUrl.endsWith(".m3u8") || (embedUrl.contains(".m3u8") && !embedUrl.contains("/stream/")) ->
                    extractDirectM3u8(embedUrl, serverName, referer)
                else ->
                    extractFromMegaPlay(embedUrl, serverName)
            }
        } catch (_: Exception) {
            ExtractedStream(emptyList(), emptyList())
        }
    }

    private fun isMegaPlayServer(serverName: String): Boolean {
        val name = serverName.lowercase().replace(" ", "").replace("-", "")
        return name in setOf(
            "vidstream2",
            "hd1",
            "hd2",
        ) || name.contains("vidstream") || name.contains("hd1") || name.contains("hd2")
    }

    private fun isMegaPlayUrl(url: String): Boolean = MEGAPLAY_HOST_REGEX.containsMatchIn(url)

    private fun parseMegaPlayMediaId(html: String): String? {
        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1)?.trim()
        if (!dataId.isNullOrBlank()) return dataId
        return FILE_ID_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun buildMegaPlayGetSourcesUrl(embedUrl: String, id: String): String {
        val base = try {
            val u = embedUrl.toHttpUrlOrNull()
            if (u != null) "${u.scheme}://${u.host}/stream/getSources"
            else "https://megaplay.buzz/stream/getSources"
        } catch (_: Exception) {
            "https://megaplay.buzz/stream/getSources"
        }

        val urlBuilder = base.toHttpUrlOrNull()?.newBuilder() ?: return "$base?id=$id"
        urlBuilder.addQueryParameter("id", id)

        try {
            val original = embedUrl.toHttpUrlOrNull()
            original?.queryParameter("s")?.let { urlBuilder.addQueryParameter("s", it) }
        } catch (_: Exception) {}

        return urlBuilder.build().toString()
    }

    private fun processMegaPlaySource(enc: String?, source: String?): String? {
        var m3u8: String? = null
        var wasDecrypted = false

        if (!enc.isNullOrBlank()) {
            try {
                val keyBytes = ByteArray(32)
                val keySrc = MEGAPLAY_AES_KEY.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(keySrc, 0, keyBytes, 0, keySrc.size.coerceAtMost(32))

                val iv = MEGAPLAY_AES_IV.toByteArray(StandardCharsets.UTF_8)

                var b64 = enc.replace('-', '+').replace('_', '/')
                while (b64.length % 4 != 0) b64 += "="
                val encrypted = Base64.getDecoder().decode(b64)

                if (encrypted.isNotEmpty() && encrypted.size % 16 == 0) {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        IvParameterSpec(iv),
                    )
                    val decrypted = cipher.doFinal(encrypted)
                    val jsonStr = String(decrypted, StandardCharsets.UTF_8)

                    val fileMatch = FILE_JSON_REGEX.find(jsonStr)
                    if (fileMatch != null) {
                        m3u8 = fileMatch.groupValues[1]
                        wasDecrypted = true
                    }
                }
            } catch (_: Exception) {}
        }

        if (m3u8.isNullOrBlank()) {
            m3u8 = source
        }

        if (m3u8.isNullOrBlank()) return null

        if (!wasDecrypted || TOKEN_PARAM_REGEX.containsMatchIn(m3u8)) {
            return m3u8
        }

        val match = PATH_KEY_REGEX.find(m3u8) ?: return m3u8

        val pathKey = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val expiry = (System.currentTimeMillis() / 1000) + 90
        val payload = "$expiry|$pathKey"

        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val signatureBytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes)

            val payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))

            val token = "$payloadB64.$signature"

            val httpUrl = m3u8.toHttpUrlOrNull()
            if (httpUrl != null) {
                httpUrl.newBuilder().setQueryParameter("token", token).build().toString()
            } else {
                "$m3u8?token=$token"
            }
        } catch (_: Exception) {
            m3u8
        }
    }

    private fun extractFromMegaPlay(embedUrl: String, serverName: String): ExtractedStream {
        val host = embedUrl.toHttpUrlOrNull()?.host ?: "megaplay.buzz"

        val pageHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$host/",
            "User-Agent" to USER_AGENT,
        )

        val reqBuilder = Request.Builder().url(embedUrl)
        pageHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        val pageBody = client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return ExtractedStream(emptyList(), emptyList())
            response.body?.string() ?: ""
        }

        val mediaId = parseMegaPlayMediaId(pageBody)
            ?: return ExtractedStream(emptyList(), emptyList())

        val getSourcesUrl = buildMegaPlayGetSourcesUrl(embedUrl, mediaId)

        val apiHeaders = mapOf(
            "Accept" to "application/json,*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to embedUrl,
            "User-Agent" to USER_AGENT,
        )

        val apiReqBuilder = Request.Builder().url(getSourcesUrl)
        apiHeaders.forEach { (k, v) -> apiReqBuilder.header(k, v) }

        val sourcesDto = client.newCall(apiReqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return ExtractedStream(emptyList(), emptyList())
            val body = response.body?.string() ?: return ExtractedStream(emptyList(), emptyList())
            json.decodeFromString<MegaPlaySourcesDto>(body)
        }

        val m3u8 = processMegaPlaySource(sourcesDto.enc, sourcesDto.sources)
            ?: return ExtractedStream(emptyList(), emptyList())

        val subtitles = sourcesDto.tracks
            ?.filter { it.label.isNotBlank() }
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

        val sources = AnikotoUtils.extractHlsSources(m3u8, client, vidHeaders, serverName)

        val introPair = sourcesDto.intro?.takeIf { it.start != 0 || it.end != 0 }?.let {
            Pair(it.start.toLong() * 1000L, it.end.toLong() * 1000L)
        }
        val outroPair = sourcesDto.outro?.takeIf { it.start != 0 || it.end != 0 }?.let {
            Pair(it.start.toLong() * 1000L, it.end.toLong() * 1000L)
        }

        return ExtractedStream(sources, subtitles, introPair, outroPair)
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
        private const val MEGAPLAY_AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val MEGAPLAY_AES_IV = "W0;27ToaUpl_P%'c"
        private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

        private val MEGAPLAY_HOST_REGEX = Regex("""megaplay\.[^/]+/stream/""", RegexOption.IGNORE_CASE)
        private val DATA_ID_REGEX = Regex("""data-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_ID_REGEX = Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val FILE_JSON_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
        private val TOKEN_PARAM_REGEX = Regex("""[?&]token=""", RegexOption.IGNORE_CASE)
        private val PATH_KEY_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

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
