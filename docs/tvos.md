# Apple TV (tvOS)

This fork preserves the Apple TV beta source originally tracked by upstream
[#590](https://github.com/goosepod/naviamp/issues/590), introduced in v2.12.0. Build from
[`tamlok/naviamp`](https://github.com/tamlok/naviamp) on a Mac; no LeTalk Apple TV download,
App Store listing or TestFlight distribution is established. Only Windows and Android pipelines
are enabled. The tvOS native, scene and Compose producer workflows are archived as `.yml.disabled`
and require explicit restoration before any hosted build or acceptance run.

## Architecture

Apple TV is a thin host of the shared runtime ([ADR 0001](architecture/0001-shared-runtime-thin-hosts.md)).
It uses the shared Television surface, Core runtime, providers, queue, connection setup (including
Aurral companions), navigation and settings. There is no tvOS controller, provider or persistence
policy.

| Module | Responsibility |
| --- | --- |
| `apps/tvos` | Xcode project `NaviampTV`, UIKit scene and window lifetime, recovery storage effects (UserDefaults, Keychain, CryptoKit), UIKit text entry, and `NaviampTvosApplication`, which wires native resources into the shared recoverable host. |
| `platforms/tvos` | Links the public tvOS BASS frameworks and supplies their inventory. |
| `platforms/apple` | Shared Apple BASS C ABI, callbacks and `AVAudioSession` effects used by iOS and tvOS. |
| `platforms/apple-media` | MediaPlayer remote commands, Now Playing and audio-session notifications. |
| `core/*/appleMain` | Shared Apple effects such as the raster presenter, SQLite driver, file storage and URL opening. |

### Storage and recovery

tvOS may purge the Caches directory between launches, so the library, artwork and audio databases
are rebuildable cache. Settings and connections live in a small encrypted recovery document in
UserDefaults, with its key in the Keychain. After a purge, Core restores the selected connection
and its Aurral companion, rebuilds derived data and does not autoplay. Keep UserDefaults content
small: tvOS warns at 512 KB and terminates at 1 MB.

When saved data cannot be opened, the shared recovery screen offers **Retry**, and on tvOS also
**Reset**, which erases this device's saved settings and connections. The server library is not
touched. iOS uses the same screen with Retry only, because its durable database has no reset effect.

## Playback

The app uses direct BASS 2.4.18.14 from Un4seen's public tvOS archive: BASS, BASSFLAC and
BASSOPUS ([inventory](../platforms/tvos/vendor/bass)). The public archive has no BASSmix, so
gapless playback, crossfade and the custom multichannel-to-stereo downmix are unavailable. Shared
engine capabilities hide those controls while preserving the saved preferences. Formats need the
BASS core decoders or FLAC/Opus; other add-ons available on iOS are not shipped.

## Building

Enable the targets with `-Pnaviamp.tvos.enabled=true`. The app host additionally needs
`-Pnaviamp.tvos.sceneHost=true` and the reviewed Compose Multiplatform tvOS artifacts described in
[`build-support/tvos-compose`](../build-support/tvos-compose/README.md). The Xcode build phase passes
both properties. Use exact target tasks (Debug simulator for iteration; optimized device Release only
for archives), and keep one consistent `KONAN_DATA_DIR` per checkout: mixing native homes produces
duplicate platform KLIB errors.

Device Release builds use the canonical bundle ID `cn.com.letalk.naviamp`, matching iOS without
claiming an App Store record. Debug and simulator builds use `cn.com.letalk.naviamp.tvos`.
Storage-host, unit-test and UI-test IDs append `.storage-test-host`, `.tests` and `.uitests` to
that tvOS ID. `scripts/validate-ios-version.py` checks both projects against `APPLICATION_ID`,
including the device-only Release override, as well as `VERSION` and `VERSION_CODE`.
The deployment target is tvOS 18.5. The fork uses a fresh app sandbox and does not migrate
upstream settings or private credentials.

## Testing

- **Native BASS and shared Apple code:** `python3 scripts/run-tvos-bass-tests.py --output <new dir>
  --shared-foundation` runs Core and direct BASS tests on a fresh tvOS simulator. The
  archived `tvos-native.yml.disabled` workflow preserves the same command but does not run.
- **Full scene:** `scripts/run-tvos-scene-tests.py` builds the app against installed Compose
  artifacts and drives setup, recovery, playback and system media with the remote against a local
  fixture server. See the Compose README for the command. `tvos-scene.yml.disabled` and
  `tvos-compose-producer.yml.disabled` are inactive. The preserved producer helper accepts only
  the repository in `RELEASE_REPOSITORY` (`tamlok/naviamp`), not an upstream producer. Its expected
  active workflow path remains `.github/workflows/tvos-compose-producer.yml` for explicit future
  restoration and pin review ([hosted delivery](../build-support/tvos-compose/HOSTED.md)).
- **Live checks:** with `TEST_RUNNER_TV590_LIVE=1`, `testLiveSearchEditorRemoteActions` and
  `testLiveTogglePlayback` in `NaviampTVUITests` run against an already configured simulator.
  Compose focus is not visible to XCTest, so these follow the fixed TV layout.

Simulator acceptance requires a Mac and an explicitly selected runtime; physical Apple TV results
must not be inferred from simulator results. This fork's identity migration has not been verified
through Apple runtime execution or signing on the Windows development host.

### Animation cost

The measurements below are retained upstream evidence, not acceptance of this fork's bundle IDs.

Screen protection dims and shifts the scene on idle. The shared raster owner moves unchanged native
layers instead of rebuilding them. In the tvOS 27.2 simulator, playing through a dim and a shift
used about 4% app CPU, the same as plain playback (previously 15.75% versus 5.97%), with the waveform
painted and moving. GPU intervals and parent-surface frames have not been re-measured since that
change, and physical hardware is unmeasured.

## Release gates

- Explicitly restore the Apple workflows and approve a fork-owned hosted Compose artifact pin
  before enabling a required scene check. No Apple CI or release is currently enabled.
- Runtime acceptance at the tvOS 18.5 deployment target. Xcode 27.2 does not offer tvOS 18.x
  simulator runtimes for download; the lowest locally tested runtime is 26.5.
- Control Center Next/Previous on tvOS 26.5, and accessibility acceptance.
- GPU and parent-surface measurements for the screen-protection animation.
- App Store Connect platform setup, Apple validation, metadata and reviewer access.
- Release notes and store copy that state the BASSmix limitations above.
