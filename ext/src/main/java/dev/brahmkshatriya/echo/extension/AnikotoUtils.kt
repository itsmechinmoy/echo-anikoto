package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AnikotoUtils {

    fun vrfEncrypt(input: String): String {
        var vrf = input
        ORDER.forEach { item ->
            when (item.second) {
                "exchange" -> vrf = exchange(vrf, item.third)
                "rc4" -> vrf = rc4Encrypt(item.third[0], vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.getUrlEncoder().withoutPadding().encodeToString(vrf.toByteArray(Charsets.UTF_8))
            }
        }
        return URLEncoder.encode(vrf, "UTF-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key)
        val output = cipher.doFinal(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output)
    }

    private fun exchange(input: String, keys: List<String>): String {
        val key1 = keys[0]
        val key2 = keys[1]
        return input.map { i ->
            val idx = key1.indexOf(i)
            if (idx != -1) key2[idx] else i
        }.joinToString("")
    }

    private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
    private const val KEY_1 = "ItFKjuWokn4ZpB"
    private const val KEY_2 = "fOyt97QWFB3"
    private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
    private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
    private const val KEY_3 = "736y1uTJpBLUX"

    private val ORDER = listOf(
        Triple(1, "exchange", EXCHANGE_KEY_1),
        Triple(2, "rc4", listOf(KEY_1)),
        Triple(3, "rc4", listOf(KEY_2)),
        Triple(4, "exchange", EXCHANGE_KEY_2),
        Triple(5, "exchange", EXCHANGE_KEY_3),
        Triple(6, "reverse", emptyList()),
        Triple(7, "rc4", listOf(KEY_3)),
        Triple(8, "base64", emptyList()),
    )

    fun toWsrvUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("https://wsrv.nl/?url=") || url.startsWith("http://wsrv.nl/?url=")) return url
        val fullUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }
        return "https://wsrv.nl/?url=$fullUrl"
    }

    fun toProxiedImageHolder(url: String?, baseUrl: String): ImageHolder? {
        return toWsrvUrl(url, baseUrl)?.toImageHolder()
    }

    fun detectRelationFromTitle(title: String): String {
        return when {
            Regex("""(?i)\b(?:Season\s*1|1st\s*Season)\b""").containsMatchIn(title) -> "Season 1"
            Regex("""(?i)\b(?:Season\s*2|2nd\s*Season)\b""").containsMatchIn(title) -> "Season 2"
            Regex("""(?i)\b(?:Season\s*3|3rd\s*Season)\b""").containsMatchIn(title) -> "Season 3"
            Regex("""(?i)\b(?:Season\s*4|4th\s*Season)\b""").containsMatchIn(title) -> "Season 4"
            Regex("""(?i)\b(?:Season\s*5|5th\s*Season)\b""").containsMatchIn(title) -> "Season 5"
            Regex("""(?i)\b(?:Season\s*6|6th\s*Season)\b""").containsMatchIn(title) -> "Season 6"
            Regex("""(?i)\b(?:Final\s*Season|Part\s*2|Part\s*3|Cour\s*2)\b""").containsMatchIn(title) -> {
                Regex("""(?i)\b(Final\s*Season|Part\s*\d+|Cour\s*\d+)\b""").find(title)?.value?.trim() ?: "Sequel"
            }
            Regex("""(?i)\b(?:Movie|The\s*Movie|Film)\b""").containsMatchIn(title) -> "Movie"
            Regex("""(?i)\b(?:OVA|OAD|Special|Side\s*Story)\b""").containsMatchIn(title) -> "OVA / Special"
            Regex("""(?i)\b(?:Prequel|Prolog)\b""").containsMatchIn(title) -> "Prequel"
            Regex("""(?i)\b(?:Sequel)\b""").containsMatchIn(title) -> "Sequel"
            Regex("""(?i)\b(?:Spin-off|Spinoff)\b""").containsMatchIn(title) -> "Spin-off"
            else -> "Related"
        }
    }

    fun String.toEchoDate(): Date? {
        return try {
            val parts = this.split("-").mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) null
            else Date(parts[0], parts.getOrNull(1), parts.getOrNull(2))
        } catch (_: Exception) {
            null
        }
    }

    fun extractHlsSources(
        m3u8Url: String,
        client: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        serverPrefix: String = "",
    ): List<Streamable.Source> {
        val sources = mutableListOf<Streamable.Source>()
        try {
            val reqBuilder = Request.Builder().url(m3u8Url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val playlist = client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }

            if (playlist != null && playlist.contains("#EXT-X-STREAM-INF:")) {
                val lines = playlist.lines()
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1)
                        val height = resolution?.substringAfter("x")?.toIntOrNull() ?: 1080
                        val label = "${height}p"

                        val nextLine = lines.getOrNull(i + 1)?.trim()
                        if (!nextLine.isNullOrEmpty() && !nextLine.startsWith("#")) {
                            val query = m3u8Url.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
                            val cleanBase = m3u8Url.substringBefore("?").substringBeforeLast("/")
                            val streamUrl = if (nextLine.startsWith("http")) {
                                nextLine
                            } else if (nextLine.startsWith("/")) {
                                val origin = try {
                                    val uri = java.net.URI(m3u8Url)
                                    val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                                    "${uri.scheme}://${uri.host}$port"
                                } catch (_: Exception) {
                                    cleanBase
                                }
                                "$origin$nextLine$query"
                            } else {
                                "$cleanBase/$nextLine$query"
                            }

                            val title = if (serverPrefix.isNotEmpty()) "$serverPrefix ($label)" else label
                            sources.add(
                                Streamable.Source.Http(
                                    request = NetworkRequest(streamUrl, headers),
                                    type = Streamable.SourceType.HLS,
                                    quality = height,
                                    title = title,
                                    isVideo = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Add Auto stream as fallback
        val autoTitle = if (serverPrefix.isNotEmpty()) "$serverPrefix (Auto)" else "Auto"
        sources.add(
            Streamable.Source.Http(
                request = NetworkRequest(m3u8Url, headers),
                type = Streamable.SourceType.HLS,
                quality = 0,
                title = autoTitle,
                isVideo = true
            )
        )

        return sources.distinctBy { it.id }.sortedByDescending { it.quality }
    }
}

class MemoryCache<K : Any, V : Any>(
    private val ttlMs: Long = 10 * 60 * 1000L
) {
    private data class Entry<V>(val value: V, val expiry: Long)
    private val map = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiry) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V, customTtlMs: Long = ttlMs) {
        map[key] = Entry(value, System.currentTimeMillis() + customTtlMs)
    }

    fun clear() {
        map.clear()
    }
}
