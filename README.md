# Echo Anikoto Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) to browse, search, and stream anime from [Anikoto](https://anikototv.to) with multi-server streams, subtitle support, and AniSkip integration.

## Features

- **Home Feed**: Browse Spotlight carousel, Trending Anime, Latest Episode Releases, Top Airing, Most Popular, Most Favorite, Latest Completed, and Anime Movies.
- **Search & Filters**: Comprehensive search by title or keywords with filter options for Genre, Type (TV, Movie, OVA, ONA, Special), Season, Year, Status, and Sort Order.
- **Quick Search**: Instant predictive search suggestions with poster artwork as you type.
- **Rich Anime Details**: Poster art, synopsis, rating scores, airing status, seasons, studio, genres, alternative titles, external links (MyAnimeList, AniList, Kitsu), and trailers.
- **Rich Episode Metadata**: Powered by [Ani.zip](https://api.ani.zip) for official episode titles, TVDB/TMDB screencap thumbnails, episode overviews, runtimes, and air dates.
- **Multi-Server Streaming**: Support for multiple streaming sources including HD-1 (MegaPlay AES-256 decrypted streams with HMAC-SHA256 authenticated tokens), Vidstream, Vidcloud, and Kiwi-Stream.
- **Multi-Quality & Sub/Dub**: Discrete and adaptive HLS video resolutions (`1080p`, `720p`, `480p`, `360p`, `Auto`) in Sub (Japanese) and Dub (English).
- **Subtitles**: Multi-language subtitle tracks extracted directly from video manifests and stream providers.
- **Video Chapters & Skip (AniSkip)**: Integrates `TrackChapterClient` with AniSkip to support Opening, Ending, Mixed OP/ED, and Recap timestamps, alongside server-provided intro/outro markers.
- **Filler Detection**: Automatic episode filler detection with customizable tagging and filtering.
- **Share**: Share direct links to Anikoto anime and episode pages.

## Settings

- **Preferred Domain**: Active mirror domain selection (`anikototv.to`, etc.).
- **Preferred Quality**: Default playback resolution (`1080p`, `720p`, `480p`, `360p`, `Auto`).
- **Preferred Server**: Default streaming server preference (`HD-1`, `Vidstream-2`, `VidCloud-1`, `Kiwi-Stream`, `VidPlay-1`).
- **Preferred Audio/Sub**: Preferred audio language (`Sub (Japanese)`, `Dub (English)`, `Show All`).
- **Show Filler Tag in Episode Titles**: Appends `(Filler)` to episode names when detected.
- **Hide Filler Episodes**: Option to hide detected filler episodes from the episode list.
- **Auto-Skip OP/ED Segments**: Automatically skips Opening and Ending intervals without prompting.

## Development & Testing

### Local Testing
Run the test suite locally:
```bash
./gradlew ext:test
```

### Build Extension JAR & Android APK
```bash
./gradlew ext:shadowJar assembleDebug
```

## Author

- **Echo** / **itsmechinmoy** ([GitHub](https://github.com/itsmechinmoy))
- Repository: [echo-anikoto](https://github.com/itsmechinmoy/echo-anikoto)
