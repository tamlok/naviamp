<p align="center">
  <img src="readme-assets/naviamp-icon.png" alt="Navo icon" width="144">
</p>

# Navo

Navo is a modern music player for the libraries you already own and the services you already
use. Connect Aurral, Navidrome, another Subsonic/OpenSubsonic server, Jellyfin, or your Bandcamp collection,
then browse, discover, queue, download, and play from one polished app on desktop, Android, and iOS.

Your music stays with the server you choose. Navo brings the player.

Navo is an independent third-party client and is not affiliated with or endorsed by the
Navidrome project.

## Get Navo

Navo is the LeTalk fork of [upstream Naviamp](https://github.com/goosepod/naviamp), with an
independent application identity and release channel.

- [Source and issue tracking](https://github.com/tamlok/naviamp)
- [This fork's releases and release notes](https://github.com/tamlok/naviamp/releases)

Enabled release pipelines produce Android APKs and Windows ZIP/MSI/EXE packages. Download only
artifacts actually published on this fork's Releases page. Upstream Google Play, OEM stores,
App Store, Microsoft Store, TestFlight and Flatpak channels do not distribute this fork's identity.
No replacement store listing or public Flatpak remote is configured by this repository.

<p align="center">
  <img src="readme-assets/screenshots/desktop-now-playing-queue.png" alt="Upstream Naviamp desktop Now Playing screen and queue" width="780">
</p>

## The big hitters

- **A real library experience.** Browse albums, artists, tracks, genres, favorites, recently added
  and recently played music, random picks, and every credited artist on a track.
- **Powerful discovery.** Build Artist, Album, and Genre mixes; use Sonic Mix and Sonic Path; start
  track radio; explore related music; and let sonic autoplay keep the queue moving when the server
  supports similarity data. Aurral, connected directly or as a companion to your music server, adds
  recommended and trending artists, samples, library requests, and artist monitoring when your
  account permits it.
- **Playlists that go further.** Create and edit regular playlists, add music from throughout the
  app, save generated queues, and build reusable smart playlists on compatible servers.
- **A full Now Playing experience.** Manage Back To, Up Next, and related queues alongside waveform
  seeking, favorites, ratings, lyrics, repeat, shuffle, volume, and detailed audio metadata.
- **Internet radio.** Browse, create, edit, and play internet radio stations next to the rest of your
  music, with Stream Details, plus live song information and artwork when supplied by the stream.
- **Downloads and offline listening.** Keep music on the device, choose storage and cache behavior,
  and use an offline dashboard when the server is out of reach.
- **Serious playback controls.** ReplayGain, gapless playback, crossfade, sample-rate matching,
  configurable resampling quality, album and playlist playback profiles, and native BASS playback
  are built in.
- **Make it yours.** Album-art colors, Aurora gradients, blur and solid-color treatments, compact
  layouts, configurable metadata, waveforms, and a large collection of reactive desktop
  visualizers are all available.
- **Certificate-protected connections.** Import client identities and private server authorities for
  authenticated browsing, artwork and playback on all supported platforms.
- **One app across devices.** Navo targets macOS, Windows, Linux, Android, and iOS with shared
  product behavior, secure credential storage, and settings sync. Android Auto, Android TV,
  Navo Connect remote control (beta), and Apple CarPlay (beta) are supported; Apple TV (beta)
  can be built from source.

## Supported music services

| Service | What Navo brings |
| --- | --- |
| **Aurral** | Requires Aurral 3.x (Aurral 2.x servers are no longer supported). Connect directly, or enable an Aurral companion in another music server’s connection settings. Discovery, samples, requests and artist monitoring use Aurral; the primary server keeps its library and playback. Availability depends on server capabilities and account permissions. |
| **Navidrome** | The fullest experience, including native authentication, smart playlists, ratings, favorites, rich OpenSubsonic features, and sonic discovery when enabled on the server. |
| **Subsonic and OpenSubsonic** | Connect compatible self-hosted servers through the standard protocol. Navo detects advertised capabilities and shows only the features the server can provide. |
| **Jellyfin** | Connect directly to Jellyfin music libraries for browsing, search, playback, downloads, playlists, lyrics, and favorites. |
| **Bandcamp** | Play and download your collection through Bandcamp's Subsonic beta using credentials generated in Fan Settings. Playlist support follows the capabilities of the beta service. |

Provider capabilities differ. Navo gracefully hides or falls back from server-specific features
instead of leaving non-working controls on screen. Sonic features require a server that advertises
compatible similarity support, and smart-playlist editing remains provider-specific.

## Playback format support

Navo plays the common formats below directly with BASS or the operating system's native codecs.
`Compatibility` means Navo ships a decoder for that platform, but the format is not yet part of
the fully tested cross-platform release contract. A dash means Navo does not currently promise
that format on that platform; a Navidrome server may still make it playable by transcoding it to a
supported format.

| Audio format or stream | Android | macOS | Windows | Linux | iOS |
| --- | :---: | :---: | :---: | :---: | :---: |
| MP3, MP2, and MP1 | Yes | Yes | Yes | Yes | Yes |
| Ogg Vorbis | Yes | Yes | Yes | Yes | Yes |
| WAV | Yes | Yes | Yes | Yes | Yes |
| AIFF | Yes | Yes | Yes | Yes | Yes |
| FLAC and Ogg FLAC | Yes | Yes | Yes | Yes | Yes |
| Opus | Yes | Yes | Yes | Yes | Yes |
| AAC and AAC in M4A/MP4 | Yes | Yes (system codec) | Yes | Yes | Yes (system codec) |
| ALAC in M4A/MP4 | Yes | Yes (system codec) | Yes | Yes | Yes (system codec) |
| Monkey's Audio (`.ape`) | Compatibility | Compatibility | Compatibility | Compatibility | Compatibility |
| DSD (`.dsf`, `.dff`) | Compatibility | Compatibility | Compatibility | Compatibility | Compatibility |
| Musepack (`.mpc`) | Compatibility | Compatibility | Compatibility | Compatibility | Compatibility |
| WebM and Matroska audio | Compatibility | Compatibility | Compatibility | Compatibility | Compatibility |
| WavPack (`.wv`) | Compatibility | Compatibility | — | Compatibility | Compatibility |
| AC-3 (`.ac3`) | Compatibility | — | — | Compatibility | — |
| HLS streams and playlists | Compatibility | Compatibility | Compatibility | Compatibility | Compatibility |
| MIDI, Speex, and WMA | — | — | — | — | — |

Format support describes direct Navo playback, including downloaded originals. Transcoded
streams and downloads use the format produced by the server. MIDI is not advertised even where a
decoder is packaged because Navo does not ship the soundfont required for reliable playback.

Apple TV (beta) builds from source include only BASS, BASSFLAC, and BASSOPUS, so formats that need
other add-ons are not available there, and gapless playback, crossfade, and multichannel downmixing
are unavailable. See [Apple TV (tvOS)](docs/tvos.md).

## Navo and Navidrome feature support

Most app behavior is shared across every release platform. Server-backed features depend on the
connected account's permissions and on the capabilities the server advertises; Navo removes
controls that the active server cannot fulfill.

| Capability | Navo support | Navidrome or server requirement |
| --- | --- | --- |
| Aurral recommendations, catalog search, samples, requests, and artist monitoring | Aurral connection or companion | Aurral 3.x, server capabilities, and account permissions; requests are distinct from playable library media |
| Browse and search artists, albums, tracks, genres, favorites, recent music, and random picks | Yes | Standard Subsonic/OpenSubsonic library APIs |
| Browse every credited artist and follow artist links from a track | Yes | Rich artist-credit metadata when supplied by the server |
| Select and combine music libraries | Yes | Multiple music folders exposed by the server |
| Create, rename, reorder, edit, and delete regular playlists | Yes | Playlist write permission |
| Save the current queue or a generated mix as a playlist | Yes | Playlist write permission |
| Create and edit native smart playlists | Navidrome enhancement | Navidrome native API; includes nested rules, multiple sorts, limits, NSP JSON import, and preview against the synced library |
| Stream original-quality audio | Yes | A directly supported source format, or server transcoding |
| Stream at a selected transcoded quality | Yes | Server transcoding support |
| Download originals or transcoded copies for offline listening | Yes | Download permission; transcoded downloads require server support |
| Favorite tracks, albums, and artists | Yes | Server favorite/star APIs |
| Rate tracks from one to five stars | Yes | Server rating support |
| Display plain, time-synced, and enhanced lyrics | Server-dependent | Legacy lyrics or the OpenSubsonic `songLyrics` extension; enhanced lyrics use version 2 |
| Build artist, album, genre, and track radio queues | Yes | Generated-radio endpoints |
| Sonic Mix, Sonic Path, related tracks, and sonic autoplay | Navidrome enhancement | Server advertises the OpenSubsonic `sonicSimilarity` extension and has similarity data |
| Report plays and update listening history | Yes | Scrobbling support; falls back to the standard Subsonic endpoint |
| Report precise playback position and completion | Navidrome enhancement | OpenSubsonic `playbackReport` extension |
| Resume a transcoded stream from a server-side offset | Navidrome enhancement | OpenSubsonic `transcodeOffset` extension |
| Browse, create, edit, delete, and play internet radio stations | Yes | Internet-radio station APIs for saved stations |
| Internet-radio Stream Details, live metadata and artwork | Yes | Metadata and supported image URLs supplied by the stream; known station logos and other artwork fallbacks apply |
| Authenticate with a renewable native session token | Navidrome enhancement | Navidrome native authentication; standard token-and-salt authentication remains available |
| Gapless playback, crossfade, ReplayGain, equalizer, sample-rate matching, and downmixing | Yes | Performed locally by Navo's BASS playback engine |
| Waveform seeking, audio metadata, lyrics timing, and visualizers | Yes | Performed locally; metadata and lyrics depend on the source |
| Android Auto | Android only | Android host and a connected media-capable vehicle or head unit |
| Apple CarPlay | iPhone only (beta) | iPhone app and a CarPlay-capable vehicle or head unit; search requires a supported iOS version |
| Podcasts, server chat, public shares, and jukebox control | Not currently supported | — |

## Screenshots

These retained screenshots show upstream Naviamp. Navo uses the same shared layouts with its new name.

<table>
  <tr>
    <td><img src="readme-assets/screenshots/compact-now-playing.png" alt="Compact Now Playing screen" width="250"></td>
    <td><img src="readme-assets/screenshots/mobile-queue.png" alt="Now Playing queue on a narrow layout" width="250"></td>
    <td><img src="readme-assets/screenshots/mix-builders.png" alt="Artist, album, genre, Sonic Path, and Sonic Mix builders" width="250"></td>
  </tr>
  <tr>
    <td><img src="readme-assets/screenshots/playlists.png" alt="Playlist library" width="250"></td>
    <td><img src="readme-assets/screenshots/library-artists.png" alt="Artist library with alphabetical navigation" width="250"></td>
    <td><img src="readme-assets/screenshots/search.png" alt="Library search results" width="250"></td>
  </tr>
  <tr>
    <td><img src="readme-assets/screenshots/internet-radio.png" alt="Internet radio stations" width="250"></td>
    <td><img src="readme-assets/screenshots/offline-downloads.png" alt="Downloads and offline dashboard" width="250"></td>
    <td><img src="readme-assets/screenshots/settings.png" alt="Naviamp settings" width="250"></td>
  </tr>
</table>

## Discovery and playlists

Navo can turn a large library into a listening session without making you manually assemble
every queue. Artist, Album, and Genre Mix work broadly; servers with sonic similarity support also
unlock Sonic Mix, Sonic Path, related tracks, track radio, and sonic autoplay. Generated results can
be edited in the queue or saved as a normal playlist.

Development builds additionally separate generated stations from their saved batches: preview,
refresh, name and save a station, or replay the exact batch. Explicit stations keep their original
seeds and tuning while prefilling upcoming music, independently of ordinary autoplay. Home mix
recommendations use listening activity and favorites with an exploration allocation. Strict genre,
decade and artist constraints can return fewer tracks rather than silently changing the mix.
Radio groups Mix Builders, Stations and Saved stations under Server Radio, with Internet Radio in
a separate tab. Home retains personalized mixes, listening history and library discovery.
Station-preserving Connect transfer requires updated peers; settings backup/sync uses schema 2.

Genre Mix can organize the genre tags found on your server into a library-specific MusicBrainz
hierarchy. Expand a broad genre to browse its subgenres, or select the parent to include every
matching descendant in one mix. Tags that do not map cleanly stay visible, and smaller or sparsely
matched libraries retain the complete flat browser.

Regular playlist workflows live wherever you are browsing: add tracks, albums, artists, search
results, downloads, and generated mixes; create a playlist without leaving the picker; reorder and
remove tracks; or start a playlist in order or shuffled. Compatible servers can also expose smart
playlists whose rules keep the contents fresh over time. Navidrome smart playlists include
genre-aware suggestions and can preview supported rules against the synced library before saving.

## Playback and presentation

Navo uses the BASS audio engine and combines its playback controls with a flexible shared UI:

- Back To, Up Next, and Related queue views with queue editing.
- Waveform seeking, synced and unsynced lyrics, favorite and rating actions, and clickable artist
  credits.
- ReplayGain, gapless playback, crossfade, sample-rate matching, and resampler quality controls.
- Per-album and per-playlist playback profiles for transition and ReplayGain overrides.
- Configurable artwork, colors, gradients, blur, metadata, long-text behavior, and compact layouts.
- Desktop visualizers ranging from reactive bars and fluid gradients to particles, terrain, tunnels,
  vinyl grooves, and album art.
- Internet-radio playback, live artwork and metadata, and Stream Details with available station, broadcast, and audio information.
- Download, cache, and offline controls for listening away from the server.

## Platforms and downloads

Android phone, tablet, Android TV and Google TV use one package, `cn.com.letalk.naviamp`.
Debug builds use `.v2test`; benchmark builds use `.benchmark`. Windows uses the same canonical
application identity with a separate installer family, installation directory and Start-menu group.

The iOS and macOS bundle ID and Linux/Flatpak package ID are also `cn.com.letalk.naviamp`.
tvOS development/simulator builds use `cn.com.letalk.naviamp.tvos`; device Release builds share
the iOS bundle ID. Apple and Linux source/build configurations remain available, but their CI
and release pipelines are disabled. See [Apple TV](docs/tvos.md),
[iOS signing and sideloading](docs/ios-sideloading.md), and [Flatpak packaging](packaging/flatpak/README.md)
for manual build and signing requirements. Store registration and signing are separate operations.

These are new application identities, not upgrades to upstream installations. Desktop data and
credentials use isolated namespaces; development storage has a separate `.development` profile.
Existing upstream data is not read, moved or deleted. To migrate deliberately, export/import shared
settings, reconnect accounts and select download storage. Source package names and JNI symbols remain
unchanged. The Navo name change retains this fork's IDs and data. Replace older LeTalk application
bundles and re-pin outdated desktop launchers when installing the Navo-named build.

`APPLICATION_NAME` defines Navo's product and package names; `APPLICATION_ID` and `RELEASE_REPOSITORY`
retain its identity and update channel. Historical release notes and acceptance records describe
upstream versions, not verified LeTalk store availability.

## Translations

Choose System default or any of the 17 bundled interface languages in Settings. German, Spanish, and
Brazilian Portuguese cover all current string keys; the other catalogs are partial and fall back to English where text
is missing. Language preferences participate in settings export/import and sync. Upgrade receiving
devices to v2.10.0 or later before syncing one of the newly selectable language choices.


Upstream Naviamp uses [Hosted Weblate](https://hosted.weblate.org/projects/naviamp/) for community
translations. Submit Navo-specific copy changes to this fork rather than changing upstream branding.

## Building from source

Navo uses Kotlin Multiplatform, Compose Multiplatform, Gradle, SQLDelight, and native BASS
integration. You will need JDK 17 or newer, Python 3 for release verification, and the Android SDK or platform packaging tools for the
targets you want to build.

```shell
git clone https://github.com/tamlok/naviamp.git
cd naviamp
./gradlew check
```

Common development commands are exposed through `make`:

```shell
make help
make test
make desktop-test
make macos-test
make android-debug
```

- `make test` runs the complete local non-device gate, including architecture checks, migrations,
  shared/provider/platform tests, native playback verification, and aggregate coverage.
- `make coverage` verifies the aggregate coverage floor and writes a browsable report.
- `make macos-test` builds, stages, and opens a local macOS app.
- `make android-debug` builds one Android debug APK for phones, tablets, and Android TV.
- `./gradlew :apps:android:stageReleaseArtifacts` stages the unified Android APK and Google Play
  App Bundle; see [Android distribution](docs/android-distribution.md).
- Windows and Linux standalone/installer targets must run on their respective operating systems.

### Windows development build

In PowerShell 7, build the current checkout and launch the staged app:

```powershell
pwsh -NoProfile -File ./scripts/windows-dev.ps1
```

Set up a full JDK 21 with `jpackage` (`JAVA_HOME` or `java` on `PATH`), Android SDK
platform 37 (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`),
and CMake 3.22+ (`cmake` on `PATH` or `CMAKE_EXE`) with Visual Studio Build Tools,
MSVC, and the Windows SDK. The script initializes the x64 compiler and SDK
environment, including native library search paths, so an ordinary PowerShell 7
session works. It uses the active Visual Studio installation or discovers the
latest installation with MSVC x64 tools. The shared Gradle project needs the
Android SDK even for this Desktop build. GNU Make and WiX are not required.

The script runs `:apps:desktop:stageLocalTestApp` for Windows x64, then opens
`build/local-test/Navo/Navo.exe` using the existing development data profile,
separate from release app data. It builds local changes incrementally; it does not
pull Git updates, clean caches, or install an MSI. Close any app running from this
checkout's staged or Compose build image before rerunning; the script never stops
running apps and does not launch an older build when compilation fails.

### Release and verification guidance

The [v2.12.0 readiness record](docs/releases/v2.12.0-readiness.md) describes the historical upstream
release. Current fork packaging and enabled checks follow the [development workflow](docs/development-workflow.md).

Shared test guidance is documented in [Testing](docs/testing.md). The shared
architecture and platform boundaries are documented in
[ADR 0001: Shared Runtime and Thin Platform Hosts](docs/architecture/0001-shared-runtime-thin-hosts.md).

## Contributing

GitHub is Navo's source of truth. Use a focused, short-lived branch and submit the result through
a pull request. Issues are optional, and branch names do not require an issue number. Link an
existing related issue when applicable. See the [development workflow](docs/development-workflow.md)
for branch, verification, and release rules.

## Project layout

```text
apps/             Thin Android, Desktop, iOS, and Apple TV application hosts
core/domain/      Shared models, playback, discovery, settings, and provider contracts
core/storage/     Shared SQLDelight persistence and migrations
core/ui/          Shared Compose UI and platform UI seams
providers/        Navidrome/OpenSubsonic (with Aurral and Bandcamp) and Jellyfin providers
platforms/        Narrow operating-system and native playback adapters
native/           Native BASS playback and visualizer support
readme-assets/    Images used by this README
```

## License

Navo is licensed under the GNU General Public License v3.0, with additional permission to link
and distribute the application with BASS. See [LICENSE](LICENSE), the
[BASS linking exception](BASS-LINKING-EXCEPTION.md), and
[third-party notices](THIRD_PARTY_NOTICES.md).

BASS and its add-ons are separately licensed. Navo currently uses BASS under its free
non-commercial terms; commercial use requires the appropriate BASS license for every supported
platform.
