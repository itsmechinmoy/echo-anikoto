package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ResultResponseDto(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
data class ServerResponseDto(
    val result: ServerResultDto,
)

@Serializable
data class ServerResultDto(
    val url: String,
    @SerialName("skip_data") val skipData: SkipDataDto? = null,
)

@Serializable
data class SkipDataDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)

@Serializable
data class SourceResponseDto(
    val enc: String? = null,
    @Serializable(with = MegaPlaySourcesFieldSerializer::class) val sources: String? = null,
    val tracks: List<TrackDto>? = null,
    val intro: MegaPlaySkipDto? = null,
    val outro: MegaPlaySkipDto? = null,
)

@Serializable
data class MegaPlaySourcesDto(
    val enc: String? = null,
    @Serializable(with = MegaPlaySourcesFieldSerializer::class) val sources: String? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
    val intro: MegaPlaySkipDto? = null,
    val outro: MegaPlaySkipDto? = null,
)

@Serializable
data class MegaPlayTrackDto(
    val file: String,
    val label: String = "",
)

@Serializable
data class MegaPlaySkipDto(
    val start: Int = 0,
    val end: Int = 0,
)

object MegaPlaySourcesFieldSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonObject -> element["file"]?.jsonPrimitive?.content
            is JsonArray -> element.firstOrNull()?.let {
                when (it) {
                    is JsonObject -> it["file"]?.jsonPrimitive?.content
                    is JsonPrimitive -> it.content
                    else -> null
                }
            }
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?): Unit = throw UnsupportedOperationException("Serialization not supported")
}

@Serializable
data class TrackDto(
    val file: String,
    val kind: String = "",
    val label: String = "",
)

@Serializable
data class MapperServerDto(
    val sub: MapperLinkDto? = null,
    val dub: MapperLinkDto? = null,
)

@Serializable
data class MapperLinkDto(
    val url: String,
)

object SourcesSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String = when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
        is JsonObject -> element["file"]?.jsonPrimitive?.content
        is JsonArray -> element.firstOrNull()?.let {
            when (it) {
                is JsonObject -> it["file"]?.jsonPrimitive?.content
                is JsonPrimitive -> it.content
                else -> null
            }
        }
        is JsonPrimitive -> element.content
    } ?: throw IllegalStateException("No valid m3u8 found in sources")

    override fun serialize(encoder: Encoder, value: String): Unit = throw UnsupportedOperationException("Serialization not supported")
}

// --- AniZip DTOs ---

@Serializable
data class AniZipResponseDto(
    val titles: Map<String, String>? = null,
    val episodes: Map<String, AniZipEpisodeDto>? = null,
    val episodeCount: Int? = null,
    val specialCount: Int? = null,
    val images: List<AniZipImageDto>? = null,
    val mappings: AniZipMappingsDto? = null,
)

@Serializable
data class AniZipMappingsDto(
    val animeplanet_id: String? = null,
    val kitsu_id: Int? = null,
    val mal_id: Int? = null,
    val anilist_id: Int? = null,
    val anisearch_id: Int? = null,
    val anidb_id: Int? = null,
    val livechart_id: Int? = null,
    @SerialName("thetvdb_id") val thetvdb_id: Int? = null,
    @SerialName("themoviedb_id") val themoviedb_id: Int? = null,
)

@Serializable
data class AniZipImageDto(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniZipEpisodeDto(
    val tvdbId: Long? = null,
    val episode: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val title: Map<String, String>? = null,
    val airDate: String? = null,
    val airdate: String? = null,
    val length: Long? = null,
    val runtime: Long? = null,
    val overview: String? = null,
    val summary: String? = null,
    val image: String? = null,
    val rating: String? = null,
) {
    fun getEnglishOrRomajiTitle(): String? {
        return title?.get("en")
            ?: title?.get("x-jat")
            ?: title?.get("ja")
            ?: title?.values?.firstOrNull()
    }
}

// --- AniSkip DTOs ---

@Serializable
data class AniSkipResponseDto(
    val found: Boolean = false,
    val results: List<AniSkipResultDto> = emptyList(),
    val message: String? = null,
    val statusCode: Int = 200,
)

@Serializable
data class AniSkipResultDto(
    val interval: AniSkipIntervalDto,
    val skipType: String,
    val skipId: String = "",
    val episodeLength: Double = 0.0,
)

@Serializable
data class AniSkipIntervalDto(
    val startTime: Double,
    val endTime: Double,
)
