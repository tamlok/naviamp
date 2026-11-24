package app.naviamp.android

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.naviamp.presentation.NaviampExternalMediaItem
import app.naviamp.presentation.NaviampExternalPlaybackSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads the framework metadata callbacks used by AAOS system media and instrument-cluster hosts. */
class AndroidExternalPlaybackPublicationInstrumentedTest {
    @Test
    fun frameworkControllerReceivesCanonicalMetadataAndLocalArtworkUris() = withSession { session, controller ->
        val current = NaviampExternalMediaItem(
            mediaId = "track-1",
            title = "Track",
            subtitle = "Artist",
            description = "Canonical Album (2026)",
            albumTitle = "Canonical Album",
        )
        val artworkUri = "content://app.naviamp.android.v2test.artwork/test-cover"
        val metadata = publish(session, controller, NaviampExternalPlaybackSnapshot(
            current = current,
            durationMillis = 180_000L,
        ), artworkUri)

        assertEquals("track-1", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals("Track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Canonical Album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals(180_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        for (key in artworkKeys) assertEquals(artworkUri, metadata.getString(key))
        assertEquals(artworkUri, metadata.description.iconUri.toString())
    }

    @Test
    fun replacementAndEmptySnapshotClearPriorTrackMetadata() = withSession { session, controller ->
        publish(session, controller, NaviampExternalPlaybackSnapshot(
            current = NaviampExternalMediaItem("track-1", "Track", "Artist", albumTitle = "Album"),
            durationMillis = 180_000L,
        ), "content://app.naviamp.android.v2test.artwork/old-cover")

        val live = publish(session, controller, NaviampExternalPlaybackSnapshot(
            current = NaviampExternalMediaItem("station", "Live radio", "Station"),
            isLive = true,
        ))
        assertEquals("station", live.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals("Live radio", live.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Station", live.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertNull(live.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertFalse(live.containsKey(MediaMetadata.METADATA_KEY_DURATION))
        for (key in artworkKeys) assertNull(live.getString(key))
        assertNull(live.description.iconUri)

        val empty = publish(session, controller, NaviampExternalPlaybackSnapshot())
        assertNull(empty.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals("", empty.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("", empty.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertNull(empty.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertFalse(empty.containsKey(MediaMetadata.METADATA_KEY_DURATION))
        for (key in artworkKeys) assertNull(empty.getString(key))
    }

    private fun withSession(test: (MediaSessionCompat, MediaController) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSessionCompat(context, "NaviampPublicationRegression")
        try {
            val controller = MediaController(context, session.sessionToken.token as MediaSession.Token)
            test(session, controller)
        } finally {
            session.release()
        }
    }

    private fun publish(
        session: MediaSessionCompat,
        controller: MediaController,
        snapshot: NaviampExternalPlaybackSnapshot,
        artworkUri: String? = null,
    ): MediaMetadata {
        val received = CountDownLatch(1)
        val observed = AtomicReference<MediaMetadata>()
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                observed.set(metadata)
                received.countDown()
            }
        }
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        try {
            session.setMetadata(snapshot.androidMediaMetadata(artworkUri))
            assertTrue(received.await(5, TimeUnit.SECONDS), "Framework metadata callback did not arrive")
            return assertNotNull(observed.get())
        } finally {
            controller.unregisterCallback(callback)
        }
    }

    private val artworkKeys = listOf(
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    )
}
