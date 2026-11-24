# Sideloading Navo on iPhone or iPad

The LeTalk fork currently supports iOS through source builds from
[`tamlok/naviamp`](https://github.com/tamlok/naviamp). Only Windows and Android pipelines are
enabled: Apple workflows are disabled, and this fork does not currently provide an automated
unsigned IPA, App Store listing or TestFlight distribution.

The Xcode app bundle ID is `cn.com.letalk.naviamp`, mirrored from root `APPLICATION_ID`.
Unit tests use `.tests` and the Keychain test host uses `.keychain-test-host` suffixes.
The display name and `Navo.app` product mirror root `APPLICATION_NAME`; the Xcode project,
`Naviamp` scheme and Swift module, and `NaviampShared` framework retain their technical names.
This is an independent installation with a fresh sandbox. Provider credentials use the actual
host bundle ID plus `.provider-credentials`; the app does not read or migrate upstream Keychain
items. Apple runtime execution and signing for this identity remain unverified on Windows.

## Before installing

- Build `apps/ios/Naviamp.xcodeproj` on a Mac with Xcode and select your own Apple development
  Team to provision and install on a connected device. Use the repository's existing Gradle/Xcode
  build configuration; the identity change does not provide signing credentials or provisioning.
- Enable Developer Mode on the destination device when iOS requests it.
- Keep Apple credentials and signing material on your own computer. Do not reuse upstream app
  records or developer credentials for this fork.

A free Apple Account can provision apps through Xcode using a Personal Team, but the provisioning
profile expires after seven days and must then be renewed. Apple currently limits a Personal Team
to three registered devices and three installed apps per device, with up to ten temporary App IDs.

Members of the paid Apple Developer Program can instead create longer-lived development or Ad Hoc
signatures. An Ad Hoc IPA only works on device identifiers included in its provisioning profile;
Apple allows up to 100 registered iPhones and 100 registered iPads per membership year.

## Locally exported builds

If you export an unsigned device IPA from your own source build, it is not directly installable.
Sign it locally with your own Apple identity using a trusted tool that supports re-signing.
An iOS device build targets physical arm64 iPhone and iPad devices, not the simulator.

On a Mac, `bash scripts/build-ios-unsigned-ipa.sh` creates
`build/ios-unsigned/Navo.xcarchive` and
`build/ios-unsigned/distributions/Navo-<VERSION>-ios-unsigned.ipa`, alongside
`Navo-iOS-Sideloading.md`. The IPA contains `Payload/Navo.app` with the `Navo` executable.
These product and artifact names derive from `APPLICATION_NAME`; the script still builds
the `Naviamp` scheme and does not sign the app.

No downloaded or automated Apple artifact is promised by the current release pipelines.

Upstream TestFlight preparation is retained only as [historical reference](testflight/README.md),
not an installation option or evidence of a LeTalk app registration.
