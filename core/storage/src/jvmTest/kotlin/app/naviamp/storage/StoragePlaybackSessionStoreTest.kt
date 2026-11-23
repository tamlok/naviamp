package app.naviamp.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.ProviderMediaSourceConnection
import app.naviamp.domain.playback.PlaylistPlaybackBookmark
import app.naviamp.domain.settings.PlaybackSessionSettings
import app.naviamp.domain.playback.PlaybackProfile
import app.naviamp.domain.playback.PlaybackProfileTarget
import app.naviamp.domain.playback.PlaybackProfileTargetType
import app.naviamp.domain.playback.PlaybackTransitionMode
import app.naviamp.domain.queue.PlaybackQueueGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoragePlaybackSessionStoreTest {
    @Test
    fun reopeningTheDatabasePreservesDuplicateOccurrencesCursorAndQueueProfiles() {
        val directory = java.nio.file.Files.createTempDirectory("naviamp-session-restart").toFile()
        val url = "jdbc:sqlite:${directory.resolve("session.db").absolutePath}"
        val session = PlaybackSessionSettings.fromTracks(
            tracks = listOf(testTrack("duplicate"), testTrack("duplicate"), testTrack("next")),
            currentIndex = 1, playNextCount = 1, positionSeconds = 73.0,
            queueGroups = listOf(PlaybackQueueGroup(
                id = "album", target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album"),
                label = "Album", startIndex = 0, endIndexExclusive = 3,
                profile = PlaybackProfile(transitionMode = PlaybackTransitionMode.Gapless),
            )),
        )!!.copy(nowPlayingOpen = true)
        try {
            val first = JdbcSqliteDriver(url)
            try {
                NaviampStorageDatabase.Schema.create(first)
                StoragePlaybackSessionStore(NaviampStorageDatabase(first).naviampStorageQueries, { 42L })
                    .savePlaybackSession(session, "source")
            } finally { first.close() }
            val reopened = JdbcSqliteDriver(url)
            try {
                val store = StoragePlaybackSessionStore(NaviampStorageDatabase(reopened).naviampStorageQueries, { 43L })
                assertEquals(session, store.loadPlaybackSession("source"))
                assertNull(store.loadPlaybackSession("different-source"))
            } finally { reopened.close() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun storesRestoresAndDeletesASourceScopedSession() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NaviampStorageDatabase.Schema.create(driver)
            val database = NaviampStorageDatabase(driver)
            val store = StoragePlaybackSessionStore(database.naviampStorageQueries, { 42L })
            val session = PlaybackSessionSettings.fromTracks(
                tracks = listOf(testTrack("track")),
                currentIndex = 0,
                queueGroups = listOf(
                    PlaybackQueueGroup(
                        id = "album-group",
                        target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album"),
                        label = "Album",
                        startIndex = 0,
                        endIndexExclusive = 1,
                        profile = PlaybackProfile(transitionMode = PlaybackTransitionMode.Gapless),
                    ),
                ),
                positionSeconds = 12.5,
            )!!

            store.savePlaybackSession(session, "source")

            assertEquals(session, store.loadPlaybackSession("source"))
            val performance = store.performanceSnapshot()
            assertTrue(assertNotNull(performance.readMillis) >= 0.0)
            assertTrue(assertNotNull(performance.decodeMillis) >= 0.0)
            assertTrue(assertNotNull(performance.encodeMillis) >= 0.0)
            assertTrue(assertNotNull(performance.writeMillis) >= 0.0)
            assertTrue((performance.payloadCharacters ?: 0) > 0)

            store.savePlaybackSession(null, "source")

            assertNull(store.loadPlaybackSession("source"))
            assertNull(store.loadPlaybackSession(null))
            assertNull(store.loadPlaybackSession(""))
            store.savePlaybackSession(session, "")
            assertNull(store.loadPlaybackSession(""))
        } finally {
            driver.close()
        }
    }

    @Test
    fun updatesCursorStateWithoutRewritingAnUnchangedQueue() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NaviampStorageDatabase.Schema.create(driver)
            val store = StoragePlaybackSessionStore(NaviampStorageDatabase(driver).naviampStorageQueries, { 42L })
            val first = PlaybackSessionSettings.fromTracks(
                tracks = listOf(testTrack("one"), testTrack("two")),
                currentIndex = 0,
                positionSeconds = 10.0,
            )!!

            store.savePlaybackSession(first, "source")
            assertEquals(true, store.performanceSnapshot().queueRewritten)

            val second = first.copy(currentIndex = 1, positionSeconds = 20.0)
            store.savePlaybackSession(second, "source")

            assertEquals(false, store.performanceSnapshot().queueRewritten)
            assertEquals(second, store.loadPlaybackSession("source"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun readsLegacyJsonAndConvertsItOnTheNextSave() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NaviampStorageDatabase.Schema.create(driver)
            val queries = NaviampStorageDatabase(driver).naviampStorageQueries
            val store = StoragePlaybackSessionStore(queries, { 42L })
            val session = PlaybackSessionSettings.fromTracks(
                tracks = listOf(testTrack("legacy")),
                currentIndex = 0,
                positionSeconds = 12.0,
            )!!
            queries.upsertPlaybackSession("source", kotlinx.serialization.json.Json.encodeToString(session), 1L)

            assertEquals(session, store.loadPlaybackSession("source"))

            store.savePlaybackSession(session.copy(positionSeconds = 15.0), "source")

            assertNull(queries.selectPlaybackSession("source").executeAsOneOrNull())
            assertEquals(listOf("legacy"), queries.selectPlaybackSessionQueueTrackIds("source").executeAsList())
            assertEquals(15.0, store.loadPlaybackSession("source")?.positionSeconds)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reopeningPreservesIndependentPlaylistBookmarksAfterSessionDeletion() {
        val directory = java.nio.file.Files.createTempDirectory("naviamp-playlist-bookmarks").toFile()
        val url = "jdbc:sqlite:${directory.resolve("bookmarks.db").absolutePath}"
        val first = PlaylistPlaybackBookmark(TrackId("duplicate"), 1, 73.25)
        val second = PlaylistPlaybackBookmark(TrackId("second-track"), 0, 24.5)
        val otherSource = PlaylistPlaybackBookmark(TrackId("other-track"), 2, 98.0)
        try {
            val sourceIds = JdbcSqliteDriver(url).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                NaviampStorageDatabase.Schema.create(driver)
                val queries = NaviampStorageDatabase(driver).naviampStorageQueries
                val source = bookmarkSource(queries, "first-user")
                val other = bookmarkSource(queries, "other-user")
                val store = StoragePlaybackSessionStore(queries, { 42L })
                val bookmarks = store.playlistBookmarks
                bookmarks.saveBookmark(source, "first", first.copy(positionSeconds = 10.0))
                bookmarks.saveBookmark(source, "first", first)
                bookmarks.saveBookmark(source, "second", second)
                bookmarks.saveBookmark(other, "first", otherSource)
                store.savePlaybackSession(
                    PlaybackSessionSettings.fromTracks(listOf(testTrack("active-session")), 0),
                    source,
                )
                store.savePlaybackSession(null, source)
                assertNull(store.loadPlaybackSession(source))
                source to other
            }
            JdbcSqliteDriver(url).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                val store = StoragePlaybackSessionStore(NaviampStorageDatabase(driver).naviampStorageQueries, { 43L })
                val bookmarks = store.playlistBookmarks
                assertNull(store.loadPlaybackSession(sourceIds.first))
                assertEquals(first, bookmarks.loadBookmark(sourceIds.first, "first"))
                assertEquals(second, bookmarks.loadBookmark(sourceIds.first, "second"))
                assertEquals(otherSource, bookmarks.loadBookmark(sourceIds.second, "first"))
                assertNull(bookmarks.loadBookmark(sourceIds.second, "second"))
                assertNull(bookmarks.loadBookmark("missing-source", "first"))
                bookmarks.saveBookmark(sourceIds.first, "first", null)
                assertNull(bookmarks.loadBookmark(sourceIds.first, "first"))
                assertEquals(second, bookmarks.loadBookmark(sourceIds.first, "second"))
                assertEquals(otherSource, bookmarks.loadBookmark(sourceIds.second, "first"))
            }
            JdbcSqliteDriver(url).use { driver ->
                val bookmarks = StoragePlaybackSessionStore(
                    NaviampStorageDatabase(driver).naviampStorageQueries,
                    { 44L },
                ).playlistBookmarks
                assertNull(bookmarks.loadBookmark(sourceIds.first, "first"))
                assertEquals(second, bookmarks.loadBookmark(sourceIds.first, "second"))
                assertEquals(otherSource, bookmarks.loadBookmark(sourceIds.second, "first"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cacheClearPreservesBookmarksWhileSourceAndDatabaseDeletionCascade() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            NaviampStorageDatabase.Schema.create(driver)
            val database = NaviampStorageDatabase(driver)
            val queries = database.naviampStorageQueries
            val catalog = StorageCoreRepositoryCatalog(
                database = database,
                credentialProtector = PassthroughStorageCredentialProtector,
                nowEpochMillis = { 42L },
                databaseLabel = "test.db",
                deleteKnownAudioCacheFile = { true },
                deleteKnownDownloadFile = { true },
            )
            val source = bookmarkSource(queries, "first-user")
            val other = bookmarkSource(queries, "other-user")
            val first = PlaylistPlaybackBookmark(TrackId("first-track"), 1, 12.5)
            val second = PlaylistPlaybackBookmark(TrackId("second-track"), 0, 25.0)
            val bookmarks = catalog.playbackSessions.playlistBookmarks
            bookmarks.saveBookmark(source, "first", first)
            bookmarks.saveBookmark(source, "second", second)
            bookmarks.saveBookmark(other, "first", second)
            assertFails { bookmarks.saveBookmark("missing-source", "first", first) }
            queries.upsertImage("https://example.test/cover", byteArrayOf(1), 1L, 1L, 1L)
            queries.upsertResponse("cache-key", "navidrome", "playlist", "first", "[]", 1L, 1L)
            queries.upsertCachedAudio(source, "first-track", "original", "/owned/cache.flac", 10L, "audio/flac", 1L, 1L)

            catalog.maintenance.clearCacheData()

            assertEquals(0L, queries.imageCacheCount().executeAsOne())
            assertEquals(0L, queries.responseCacheCount().executeAsOne())
            assertNull(queries.selectAnyCachedAudio(source, "first-track").executeAsOneOrNull())
            assertEquals(first, bookmarks.loadBookmark(source, "first"))
            assertEquals(second, bookmarks.loadBookmark(source, "second"))
            assertEquals(second, bookmarks.loadBookmark(other, "first"))

            catalog.mediaSources.deleteMediaSource(source)

            assertNull(bookmarks.loadBookmark(source, "first"))
            assertNull(bookmarks.loadBookmark(source, "second"))
            assertEquals(second, bookmarks.loadBookmark(other, "first"))

            catalog.maintenance.clearAll()

            assertNull(bookmarks.loadBookmark(other, "first"))
            assertEquals(0L, queries.mediaSourceCount().executeAsOne())
        }
    }

    @Test
    fun migrationAfterBaselineThirtyAddsBookmarksWithoutChangingTheExistingSession() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            NaviampStorageDatabase.Schema.create(driver)
            val database = NaviampStorageDatabase(driver)
            val source = bookmarkSource(database.naviampStorageQueries, "listener")
            val session = PlaybackSessionSettings.fromTracks(listOf(testTrack("current")), 0, positionSeconds = 42.0)!!
            val store = StoragePlaybackSessionStore(database.naviampStorageQueries, { 42L })
            store.savePlaybackSession(session, source)
            driver.execute(null, "DROP TABLE playlist_playback_bookmark", 0)

            NaviampStorageDatabase.Schema.migrate(driver, 31L, NaviampStorageDatabase.Schema.version)

            assertNotNull(database.naviampStorageQueries.selectMediaSourceById(source).executeAsOneOrNull())
            assertEquals(session, store.loadPlaybackSession(source))
            assertNull(store.playlistBookmarks.loadBookmark(source, "playlist"))
            val bookmark = PlaylistPlaybackBookmark(TrackId("current"), 0, 42.0)
            store.playlistBookmarks.saveBookmark(source, "playlist", bookmark)
            assertEquals(bookmark, store.playlistBookmarks.loadBookmark(source, "playlist"))
        }
    }
}

private fun testTrack(id: String) = Track(
    id = TrackId(id),
    title = "Track",
    artistName = "Artist",
    albumTitle = null,
    durationSeconds = null,
    coverArtId = null,
    audioInfo = null,
    replayGain = null,
)

private fun bookmarkSource(queries: NaviampStorageQueries, user: String): String =
    StorageMediaSourceStore(queries, nowMillis = { 42L }).upsertProviderMediaSource(
        ProviderMediaSourceConnection("Server", "https://example.test", user, "token", "salt"),
        cacheNamespace = "navidrome:$user",
        providerId = "navidrome",
    ).id
