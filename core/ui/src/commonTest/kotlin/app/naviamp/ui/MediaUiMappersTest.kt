package app.naviamp.ui

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Playlist
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistId
import app.naviamp.domain.home.FavoriteArtistsStatus
import app.naviamp.domain.home.HomeContent
import app.naviamp.domain.home.HomeDate
import app.naviamp.domain.navibeat.NavibeatMix
import app.naviamp.domain.navibeat.NavibeatMixDate
import app.naviamp.domain.navibeat.NavibeatMixMetadata
import app.naviamp.domain.radio.albumRecentRadioStream
import app.naviamp.domain.radio.randomAlbumRecentRadioStream
import app.naviamp.domain.settings.FavoriteArtistSort
import app.naviamp.domain.settings.HomeSectionIds
import app.naviamp.domain.settings.HomeSectionLayout
import app.naviamp.domain.settings.HomeSectionPageLayout
import app.naviamp.domain.settings.HomeSectionPresentationSettings
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.homeSectionPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavibeatHomeCardMapperTest {
    @Test fun mixBuildersCarryTheirGeneratedArtworkInHomeAndCollectionGrids() {
        val section = HomeContent().toSharedHomeUi(coverArtUrl = { null }).collectionSections
            .single { it.id == app.naviamp.domain.settings.HomeSectionIds.MixBuilders }
        assertEquals(listOf("artist", "album", "genre"), section.items.map { it.artworkKey })
        section.items.forEach { assertEquals(SharedHomeCollectionArtwork.MixBuilderGenerated, it.artwork) }
    }

    @Test
    fun favoriteArtistsBecomeASortedTranslatableHomeSection() {
        val home = HomeContent(
            favoriteArtists = listOf(
                Artist(ArtistId("z"), "Zulu", "2026-01-02T00:00:00Z"),
                Artist(ArtistId("a"), "alpha", "2026-01-01T00:00:00Z"),
            ),
        )

        val section = home.toSharedHomeUi(coverArtUrl = { null })
            .collectionSections
            .single { it.id == app.naviamp.domain.settings.HomeSectionIds.FavoriteArtists }

        assertEquals(SharedHomeCollectionTitleResource.Recommendations, section.titleResource)
        assertEquals(listOf("alpha", "Zulu"), section.items.map { it.mediaItem.title })
        assertEquals(listOf(SharedHomeCollectionItemAction.OpenArtist, SharedHomeCollectionItemAction.OpenArtist), section.items.map { it.action })
    }

    @Test
    fun recommendationsKeepSongsFirstAndSortArtistsAndAlbumsWithinTheirGroups() {
        val home = HomeContent(
            supportsFavoriteSongs = true,
            favoriteArtists = listOf(
                Artist(ArtistId("z"), "Zulu", "2026-01-02T00:00:00Z"),
                Artist(ArtistId("a"), "alpha", "2026-01-01T00:00:00Z"),
            ),
            favoriteArtistLastPlayed = mapOf(ArtistId("z") to "2026-01-03T00:00:00Z"),
            favoriteArtistsStatus = FavoriteArtistsStatus.Cached,
            favoriteAlbums = listOf(
                favoriteAlbum("album-z", "Zulu").copy(favoritedAtIso8601 = "2026-03-01T00:00:00Z"),
                favoriteAlbum("album-b", "alpha"),
                favoriteAlbum("album-a", "alpha").copy(favoritedAtIso8601 = "2026-02-01T00:00:00Z"),
                favoriteAlbum("album-upper", "Alpha"),
            ),
            recentRadioStreams = listOf(
                randomAlbumRecentRadioStream(favoriteAlbum("album-b", "alpha")),
                albumRecentRadioStream(favoriteAlbum("album-z", "Zulu")),
            ),
            favoriteAlbumsFailed = true,
        )
        for (sort in FavoriteArtistSort.entries) {
            val settings = InterfaceSettings(favoriteArtistSort = sort)
            val section = assertNotNull(home.toSharedRecommendationsSection(
                coverArtUrl = { "cover:$it" },
                canFavoriteAlbums = true,
                interfaceSettings = settings,
            ))
            val artistIds = if (sort == FavoriteArtistSort.Name) listOf("a", "z") else listOf("z", "a")
            val albumIds = when (sort) {
                FavoriteArtistSort.Name -> listOf("album-upper", "album-a", "album-b", "album-z")
                FavoriteArtistSort.DateFavorited -> listOf("album-z", "album-a", "album-upper", "album-b")
                FavoriteArtistSort.LastRadioPlayed -> listOf("album-b", "album-z", "album-upper", "album-a")
            }
            assertEquals(listOf("favorite-songs") + artistIds + albumIds, section.items.map { it.mediaItem.id })
            assertEquals(
                listOf(SharedMediaItemKind.Playlist, SharedMediaItemKind.Artist, SharedMediaItemKind.Artist) + List(4) { SharedMediaItemKind.Album },
                section.items.map { it.mediaKind },
            )
            assertEquals(
                listOf(SharedHomeCollectionItemAction.OpenFavoriteSongs, SharedHomeCollectionItemAction.OpenArtist, SharedHomeCollectionItemAction.OpenArtist) + List(4) { SharedHomeCollectionItemAction.OpenAlbum },
                section.items.map { it.action },
            )
            val songs = section.items.first()
            assertEquals(SharedHomeCollectionArtwork.FavoriteSongs, songs.artwork)
            assertEquals("", songs.title)
            assertEquals("", songs.subtitle)
            assertEquals("", songs.mediaItem.title)
            assertEquals("", songs.mediaItem.subtitle)
            assertFalse(songs.mediaItem.canFavorite)
            assertNull(songs.mediaItem.coverArtUrl)
            assertTrue(section.items.drop(1).all { it.artwork == SharedHomeCollectionArtwork.CoverArt })
            assertTrue(section.items.drop(1).all { it.mediaItem.canFavorite && it.mediaItem.favoriteActive })
            assertEquals("cover:${albumIds.first()}", section.items[3].mediaItem.coverArtUrl)
            assertEquals(sort, section.favoriteArtistSort)
            assertEquals(FavoriteArtistsStatus.Cached, section.favoriteArtistsStatus)
            assertTrue(section.favoriteAlbumsFailed)
            assertEquals(SharedHomeCollectionTitleResource.Recommendations, section.titleResource)
            assertEquals(section, home.toSharedHomeUi(
                coverArtUrl = { "cover:$it" },
                canFavoriteAlbums = true,
                interfaceSettings = settings,
            ).collectionSections.single { it.id == HomeSectionIds.FavoriteArtists })
        }
    }

    @Test
    fun recommendationsRetainPersistedSectionPresentationAndAllBackingItems() {
        val settings = InterfaceSettings(
            homeSectionOrder = listOf(HomeSectionIds.RecentlyAdded, HomeSectionIds.FavoriteArtists),
            homeSectionPresentations = mapOf(
                HomeSectionIds.FavoriteArtists to HomeSectionPresentationSettings(
                    homeLayout = HomeSectionLayout.List,
                    pageLayout = HomeSectionPageLayout.List,
                    homeItemLimit = 5,
                    visible = false,
                ),
            ),
        )
        val home = HomeContent(
            supportsFavoriteSongs = true,
            favoriteAlbums = List(40) { favoriteAlbum("album-$it", "Album $it") },
            recentlyAddedAlbums = listOf(favoriteAlbum("recent", "Recent")),
        )
        val mapped = home.toSharedHomeUi(coverArtUrl = { null }, interfaceSettings = settings)
        val section = mapped.collectionSections.single { it.id == HomeSectionIds.FavoriteArtists }

        assertFalse(section.visible)
        assertEquals(HomeSectionLayout.List, section.homeLayout)
        assertEquals(HomeSectionPageLayout.List, section.defaultPageLayout)
        assertEquals(settings.homeSectionPresentation(HomeSectionIds.FavoriteArtists).homeItemLimit, section.homeItemLimit)
        assertEquals(41, section.items.size)
        assertEquals("favorite-songs", section.items.first().mediaItem.id)
        assertTrue(section.items.drop(1).all { !it.mediaItem.canFavorite })
        assertTrue(mapped.collectionSections.indexOfFirst { it.id == HomeSectionIds.RecentlyAdded } < mapped.collectionSections.indexOf(section))
    }

    @Test
    fun recommendationsIncludeEachSupportedCategoryAndStatusIndependently() {
        assertNull(HomeContent().toSharedRecommendationsSection({ null }))
        val songsOnly = assertNotNull(HomeContent(supportsFavoriteSongs = true).toSharedRecommendationsSection({ null }))
        assertEquals(SharedHomeCollectionItemAction.OpenFavoriteSongs, songsOnly.items.single().action)
        val artistsOnly = assertNotNull(HomeContent(
            favoriteArtists = listOf(Artist(ArtistId("artist"), "Artist")),
        ).toSharedRecommendationsSection({ null }))
        assertEquals(SharedMediaItemKind.Artist, artistsOnly.items.single().mediaKind)
        val albumsOnly = assertNotNull(HomeContent(
            favoriteAlbums = listOf(favoriteAlbum("album", "Album")),
        ).toSharedRecommendationsSection({ null }))
        assertEquals(SharedMediaItemKind.Album, albumsOnly.items.single().mediaKind)
        for (status in listOf(FavoriteArtistsStatus.Ready, FavoriteArtistsStatus.Cached, FavoriteArtistsStatus.Failed)) {
            val section = assertNotNull(HomeContent(favoriteArtistsStatus = status).toSharedRecommendationsSection({ null }))
            assertEquals(status, section.favoriteArtistsStatus)
            assertTrue(section.items.isEmpty())
        }
        val albumFailure = assertNotNull(HomeContent(favoriteAlbumsFailed = true).toSharedRecommendationsSection({ null }))
        assertTrue(albumFailure.favoriteAlbumsFailed)
        assertTrue(albumFailure.items.isEmpty())
    }

    @Test
    fun navibeatCardsPlayByDefaultAndRemainIdentifiableForTheirActionMenu() {
        val playlist = Playlist(id = "navibeat-1", name = "Afternoon", trackCount = 20)
        val home = HomeContent(
            date = HomeDate(2026, 223),
            navibeatMixes = listOf(
                NavibeatMix(
                    playlist = playlist,
                    metadata = NavibeatMixMetadata(
                        kind = "timeofday",
                        slot = "afternoon",
                        generatedOn = NavibeatMixDate(2026, 8, 11),
                        mode = "personalized",
                        trackCount = 20,
                        description = "Afternoon mix",
                    ),
                ),
            ),
        )

        val item = home.toSharedHomeUi(coverArtUrl = { null })
            .collectionSections
            .single { it.id == SharedHomeCollectionSectionIds.NavibeatMixes }
            .items
            .single()

        assertEquals(SharedHomeCollectionItemAction.PlayPlaylist, item.action)
        assertEquals(SharedHomeCollectionArtwork.NavibeatGenerated, item.artwork)
        assertEquals("timeofday", item.artworkKey)
    }

    @Test
    fun generatedStationsCarryIconArtworkKeys() {
        val stations = HomeContent().toSharedHomeUi(coverArtUrl = { null })
            .collectionSections
            .single { it.id == "stations" }
            .items

        assertEquals(listOf("library", "random-album"), stations.map { it.artworkKey })
        stations.forEach { station ->
            assertEquals(SharedHomeCollectionArtwork.StationGenerated, station.artwork)
            assertEquals(SharedHomeCollectionItemAction.SelectStation, station.action)
        }
    }
}

private fun favoriteAlbum(id: String, title: String): Album = Album(
    id = AlbumId(id),
    title = title,
    artistName = "Artist",
    coverArtId = null,
    recentlyAddedAtIso8601 = null,
    favoritedAtIso8601 = "favorite",
)
