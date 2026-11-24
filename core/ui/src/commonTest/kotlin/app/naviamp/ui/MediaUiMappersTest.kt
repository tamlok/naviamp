package app.naviamp.ui

import app.naviamp.domain.Playlist
import app.naviamp.domain.home.HomeContent
import app.naviamp.domain.home.HomeDate
import app.naviamp.domain.navibeat.NavibeatMix
import app.naviamp.domain.navibeat.NavibeatMixDate
import app.naviamp.domain.navibeat.NavibeatMixMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class NavibeatHomeCardMapperTest {
    @Test fun mixBuildersCarryTheirGeneratedArtworkInServerRadioAndCollectionGrids() {
        val section = HomeContent().toSharedHomeUi(coverArtUrl = { null }).serverRadioSections
            .single { it.id == app.naviamp.domain.settings.HomeSectionIds.MixBuilders }
        assertEquals(listOf("artist", "album", "genre"), section.items.map { it.artworkKey })
        section.items.forEach { assertEquals(SharedHomeCollectionArtwork.MixBuilderGenerated, it.artwork) }
    }

    @Test
    fun serverSectionsLeaveHomeButKeepLayoutsCapabilitiesAndSavedArtistShapes() {
        val settings = app.naviamp.domain.settings.InterfaceSettings(
            homeSectionOrder = SharedServerRadioSectionIds.reversed(),
            homeSectionPresentations = SharedServerRadioSectionIds.associateWith {
                app.naviamp.domain.settings.HomeSectionPresentationSettings(visible = false,
                    homeLayout = app.naviamp.domain.settings.HomeSectionLayout.List)
            },
        )
        val station = app.naviamp.domain.radio.GeneratedRadioStation("saved", "Saved artist", app.naviamp.domain.radio.GeneratedRadioKind.Artist,
            saved = true, artists = listOf(app.naviamp.domain.settings.SavedArtist("artist", "Artist")))
        val mapped = HomeContent().toSharedHomeUi(coverArtUrl = { null }, interfaceSettings = settings,
            showSonicPathBuilder = true, showSonicMixBuilder = true, savedGeneratedStations = listOf(station))
        assertEquals(SharedServerRadioSectionIds, mapped.serverRadioSections.map { it.id })
        kotlin.test.assertTrue(mapped.serverRadioSections.all { it.visible })
        kotlin.test.assertTrue(mapped.isEmpty)
        kotlin.test.assertTrue(mapped.collectionSections.none { it.id in SharedServerRadioSectionIds })
        assertEquals(listOf("artist", "album", "genre", "sonic-path", "sonic-mix"),
            mapped.serverRadioSections.first().items.map { it.mediaItem.id })
        assertEquals(app.naviamp.domain.settings.HomeSectionLayout.List, mapped.serverRadioSections.first().homeLayout)
        val saved = mapped.collectionSection(SharedHomeCollectionSectionIds.SavedGeneratedStations)!!
        assertEquals(SharedMediaItemKind.Artist, saved.items.single().mediaKind)
        assertEquals(SharedHomeCollectionItemAction.StartGeneratedStation, saved.items.single().action)
        assertEquals(app.naviamp.domain.settings.HomeSectionLayout.Carousel, saved.homeLayout)
        assertEquals(10, saved.homeItemLimit)
    }


    @Test
    fun mixesForYouTilesStartAnAlbumSeededMixInsteadOfPlayingTheAlbum() {
        val home = HomeContent(
            mixAlbums = listOf(app.naviamp.domain.Album(app.naviamp.domain.AlbumId("single"), "Single", "Seed Artist", null, null)),
        )

        val item = home.toSharedHomeUi(coverArtUrl = { null })
            .collectionSections
            .single { it.id == SharedHomeCollectionSectionIds.MixesForYou }
            .items
            .single()

        assertEquals(SharedHomeCollectionItemAction.PlayAlbumMix, item.action)
        assertEquals(SharedMediaItemKind.Album, item.mediaKind)
        assertEquals("single", item.mediaItem.id)
    }

    @Test
    fun mixTileDispatchRequestsTheSharedAlbumMixCommandForEveryHost() {
        val requests = mutableListOf<NaviampMediaItemActionRequest>()
        val item = SharedHomeCollectionItemUi(
            mediaItem = SharedMediaItemUi("single", "Single", "Seed Artist"),
            mediaKind = SharedMediaItemKind.Album,
            action = SharedHomeCollectionItemAction.PlayAlbumMix,
        )

        dispatchHomeCollectionItem(
            item,
            NaviampHomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }),
            NaviampMediaActions(onTrackAction = {}, onMediaItemAction = { requests += it }),
        )

        assertEquals(listOf(NaviampMediaItemActionRequest(item.mediaItem, NaviampMediaItemCommand.PlayAlbumMix)), requests)
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
