# One Android app for phone, tablet, and TV

Navo uses one Android application module (`:apps:android`) and one release package ID
(`cn.com.letalk.navo`, from `APPLICATION_ID`). There are no phone/TV product flavors. Android's UI-mode configuration
selects the shared Standard or Television surface at runtime. Both launcher entries, the TV banner,
and optional leanback/touchscreen declarations live in the same manifest.

The former “Naviamp TV” setup/restoration text was product copy inside that shared app, not a
separate application. The shared translated setup copy now calls it Navo. Debug builds use
`cn.com.letalk.navo.v2test` and “Navo v2 Test” so they can coexist with the installed release;
that distinction applies equally to phone and TV. Benchmark/profileable variants are development
tools, not device-specific distributions.

This LeTalk fork has a new signing and installation identity. It does not update upstream
`app.naviamp.android` installations or inherit their app data or Google Play listing. Existing
data stays untouched; import shared settings and reconnect accounts deliberately. Historical
verification below describes upstream artifacts, not acceptance of this fork's packages.
The final `APPLICATION_ID` also creates fresh app-data and credential namespaces rather than updating
previous local `cn.com.letalk.naviamp` builds (including their `.v2test` variant). No previous local
settings or credentials are read or automatically migrated. The existing fork signing key is retained;
`APPLICATION_NAME` remains Navo. This is an installation-ID change, not only a display-name change.

For Navo 1.1.0 release gates and distribution status, see the [readiness record](releases/v1.1.0-readiness.md).
The dated verification sections below describe upstream builds, not this fork's release acceptance.

## Now-playing publication for Android Automotive OS

The Android playback service publishes Core's now-playing state through its existing standard
`MediaSessionCompat`. An AAOS system media host or OEM instrument-cluster consumer can observe
that framework session with `MediaSessionManager` and `MediaController`; Naviamp does not need
privileged cluster permissions or a manufacturer-specific broadcast.

- Metadata includes media ID, track title, artist, canonical album title, known duration, and
  local artwork `content://` URIs. Album metadata excludes the UI's formatted year suffix.
  Replacing the metadata clears previous artwork, album, and duration when unavailable.
- Playback state, position, queue selection, and supported controls follow the shared external
  playback bridge. Changing tracks refreshes the playback clock even at the same queue position;
  normal progress ticks do not repeatedly rebuild metadata or reset that clock.
- Artwork continues through Naviamp's existing content provider and provider-aware cache, rather
  than exposing authenticated remote artwork URLs as session metadata.

This is the publication contract, not a dedicated AAOS distribution or Google Play Automotive
qualification. The current package still has the phone/tablet/TV launcher and Android Auto
declarations. Whether media appears behind the steering wheel, its layout, and artwork support
remain the vehicle manufacturer's responsibility. See
[AOSP's cluster media-status integration](https://source.android.com/docs/automotive/displays/monitor#media-status).

Regression coverage: `NaviampCoreExternalPlaybackBridgeTest` exercises publication timing in
common code; `AndroidExternalPlaybackPublicationInstrumentedTest` exercises metadata replacement
and delivery through real framework controller callbacks without an account or network. A phone
or tablet emulator can run the latter, but cannot verify a vehicle's AAOS cluster display.

## Build and distribute

Run `./gradlew :apps:android:stageReleaseArtifacts` to produce the installable formats and their
diagnostic artifacts from the same release:

- `apps/android/build/release-artifacts/Navo-<version>-android.apk` for direct installation on
  phones, tablets, and TVs.
- `apps/android/build/release-artifacts/Navo-<version>-android.aab` for Google Play.
- `apps/android/build/release-artifacts/Navo-<version>-android-mapping.txt` for retracing
  obfuscated JVM/Kotlin crash reports.
- `apps/android/build/release-artifacts/Navo-<version>-android-native-debug-symbols.zip` for
  symbolizing native crash reports.

Release APKs and bundles use R8 full-mode code optimization, obfuscation, and optimized resource
shrinking. The project uses `proguard-android-optimize.txt` plus a narrow rule that preserves the
name-based Android BASS JNI bridge. Release native symbols use `SYMBOL_TABLE`; the packaged native
libraries remain stripped while the separate symbols archive retains function names for crash
diagnostics. Keep the mapping and native-symbol files with the exact APK/AAB that produced them.

`make android-release` uses this same task. `make android-play-release` additionally requires the
release signing environment before invoking it. `stageReleaseApk` remains a compatibility alias. The tag release workflow
requires the existing signing environment and builds both formats in one Android job. Only the APK
is attached to the public draft release; the AAB, mapping and native symbols are retained in the
`android-publishing-support` Actions artifact. Local outputs without configured release signing are
not publishable signed releases. The workflow does not upload to or publish on Google Play.

## Historical Android Gradle Plugin 9 evaluation

Naviamp remains on AGP 8.13 for this release. [AGP 9 enables built-in Kotlin and replaces the legacy
Kotlin Multiplatform Android integration](https://developer.android.com/build/releases/agp-9-0-0-release-notes).
Naviamp currently has eight shared modules that apply both
`org.jetbrains.kotlin.multiplatform` and `com.android.library`, plus Android application/library
modules that apply `org.jetbrains.kotlin.android`. Moving to AGP 9 therefore requires a coordinated
KMP Android-plugin migration across Core, providers, tests, SQLDelight, and Compose rather than a
safe packaging-only update. [R8 full mode is already the AGP 8 default](https://developer.android.com/agents/skills/performance/r8-analyzer/references/CONFIGURATION), and
`android.r8.optimizedResourceShrinking=true` enables the optimized shrinker available in AGP 8.13.
Re-evaluate AGP 9 after the shared modules can adopt the new KMP Android library plugin together.

## Release optimization baseline — #93, September 15, 2026

Measurements use fresh local `stageReleaseArtifacts` outputs from the same source tree before and
after enabling R8. Sizes are raw bytes from the APK/AAB ZIP and DEX entries; they are independent of
filesystem display-unit rounding.

| Measurement | Before | Optimized | Reduction |
| --- | ---: | ---: | ---: |
| APK | 37,543,199 bytes | 24,987,117 bytes | 33.4% |
| AAB | 28,542,710 bytes | 21,757,046 bytes | 23.8% |
| Uncompressed DEX | 43,897,240 bytes (4 files) | 7,109,332 bytes (1 file) | 83.8% |
| APK resources | 112,231 bytes | 71,020 bytes | 36.7% |

`verifyReleaseOptimization` rebuilds the staged release in pull-request verification and checks
that optimized DEX remains below 20 MB, R8 reports removed code, the JNI class name remains stable,
and both diagnostic files are nonempty. All 18 arm64-v8a native libraries in the optimized APK were
also inspected with NDK `llvm-readelf`; every load segment retains 16 KB (`0x4000`) alignment.

For Google Play, use one Naviamp listing and package with TV support enabled. Supply the TV
listing assets and complete its quality review. Separate TV tracks are optional; the unified
bundle can serve TV through the mobile track. See [Google Play's form-factor distribution guide](https://support.google.com/googleplay/android-developer/answer/13295490?hl=en).

Before shipping, install the same built APK on phone and TV, verify automatic surface selection
and D-pad/touch interaction, and complete the pending real-device performance and recovery checks.

## Verification — #82, September 14, 2026

- All 366 shared UI JVM tests passed, including maintained-translation parity. Shared UI compiled
  for Android, JVM, iOS ARM64, and iOS Simulator ARM64; the architecture guard passed.
- `stageReleaseArtifacts` produced both versioned release formats. The APK declares package
  `app.naviamp.android`, label `Naviamp`, and both launcher categories. Both APK and AAB contain
  arm64-v8a, armeabi-v7a, x86, and x86_64 native libraries. Local release outputs were unsigned;
  the release workflow continues to require signing credentials.
- `bundleDebug`, now included in CI verification, passed. Both changed workflow files parsed as
  valid YAML, and both Make release shortcuts resolve to `stageReleaseArtifacts`.
- Installed the exact same debug APK on the Pixel 10a and Android TV ARM64 emulator, preserving
  their data. Pixel opened the standard touch interface; TV opened the television setup interface
  with the heading “Set up Naviamp.” TV D-pad navigation moved focus to Show pairing code.
  The emulator initially showed a black window; waking/restarting the test app restored its UI.
- Tested APK SHA-256: `a8d469c149dd0c79293f6121ec52a73dc802e4defdc2d6972729fff923e5f860`.

This verifies packaging and automatic interface selection. It does not replace the pending physical
TV performance/recovery pass or Google Play qualification. No platform production Kotlin changed;
the Android changes are packaging configuration and shared localized copy.
