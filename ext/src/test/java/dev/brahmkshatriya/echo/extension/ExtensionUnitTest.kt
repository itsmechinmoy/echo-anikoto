package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackChapterClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = AnikotoExtension()
    private val searchQuery = "Solo Leveling"
    private val user = User("", "Test User")

    @Test
    fun testEmptySearch() = testIn("Testing Empty Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val search = extension.loadSearchFeed("").pagedDataOfFirst().loadPage(null).data
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Searching  : $searchQuery")
        val feed = extension.loadSearchFeed(searchQuery)
        println("Tabs : ${feed.tabs}")
        feed.pagedDataOfFirst().loadPage(null).data.forEach {
            println(it)
        }
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        println("Tabs : ${feed.tabs}")
        feed.pagedDataOfFirst().loadPage(null).data.forEach {
            println(it)
        }
    }

    private suspend fun searchTrack(q: String? = null): Track {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val query = q ?: searchQuery
        println("Searching : $query")
        val album = extension.loadSearchFeed(query).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Album
                    is Shelf.Lists.Items -> it.list.firstOrNull() as? Album
                    else -> null
                }
            } ?: error("Album not found for search query: $query")

        val tracks = extension.loadTracks(album)?.loadAll()
        return tracks?.firstOrNull() ?: error("No tracks found for album: ${album.title}")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println(track)
        }.also { println("time : $it") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            val streamable = track.streamables.firstOrNull() ?: error("Track has no streamable servers")
            val stream = extension.loadStreamableMedia(streamable, false)
            println(stream)
        }.also { println("time : $it") }
    }

    @Test
    fun testChapters() = testIn("Testing Chapters / AniSkip") {
        if (extension !is TrackChapterClient) error("TrackChapterClient is not implemented")
        val search = searchTrack()
        val chapters = extension.getChapters(search)
        println("Chapters count: ${chapters.size}")
        chapters.forEach {
            println("Chapter: ${it.name} [${it.startTime}ms - ${it.endTime}ms]")
        }
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val albumItem = extension.loadSearchFeed(searchQuery).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Album
                    else -> null
                }
            } ?: error("Album not found")
        val album = extension.loadAlbum(albumItem)
        println(album)
        val tracks = extension.loadTracks(album)?.loadAll()
        println("Tracks count: ${tracks?.size}")
        tracks?.take(5)?.forEach {
            println(it)
        }
    }


    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
            if (extension is LoginClient) extension.setLoginUser(user)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}
