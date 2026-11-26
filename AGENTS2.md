# Repository Guidelines

Supplementary guidance for this checkout. Read together with [AGENTS.md](AGENTS.md);
upstream rules in that file take precedence if repeated guidance here diverges.

## Project Overview

Naviamp is a Kotlin Multiplatform music player for Android, iOS, Windows, macOS, and Linux.
Shared Compose UI and native BASS playback serve Navidrome/Subsonic/OpenSubsonic, Jellyfin,
Bandcamp, and Aurral integrations. Provider capabilities vary; do not assume feature parity.

## Architecture & Data Flow

**Core is the product; hosts only supply native effects.** Begin every implementation in common
code, creating the common owner first if missing. A feature is incomplete if its behavior must be
implemented again for another platform. Never prototype product behavior in a host for later extraction. Shared ownership includes UI, actions/navigation, state, validation, capability
and status policy, orchestration, scheduling/retries, lifecycle policy, serialization, migrations,
cache/storage coordination, and complete contracts needed by another thin host.

- Browse flow: shared UI actions enter `NaviampCore`/presentation controllers, call domain provider
  and repository contracts, then publish immutable state back to Compose. Provider `commonMain`
  owns protocol/authentication/session renewal, response/model interpretation, and provider-specific
  persistence mapping; `core/storage` owns portable SQLDelight repositories and query-row mapping.
- Playback flow: Core coordinates queue/session/output policy; providers supply streams; shared
  relay/random-access sources feed native playback effects. Keep high-frequency progress separate
  from structural playback state. Preserve stream-lease cancellation and stale-session protection.
- Hosts inject database drivers, secure storage, filesystem, HTTP/TLS engines, and playback effects;
  lifecycle callbacks delegate to common owners. UI must not call provider implementations or
  generated SQLDelight queries directly. See [ADR 0001](docs/architecture/0001-shared-runtime-thin-hosts.md).

Before creating, moving, or modifying platform production code, name the concrete OS API, native
ABI, or unavoidable host lifecycle boundary that prevents common Kotlin. Valid boundaries include
Android Activity/Service/MediaSession/permissions/Android Auto, Apple audio sessions/CarPlay,
Desktop windows/Dock/taskbar/dialogs/JVM filesystem/process APIs, BASS/JNI/cinterop, and native
resource lifetimes. Adapters only translate types, perform effects, and publish results; call timing,
state transitions, retries, persistence decisions, and user-visible policy remain common.
`expect`/`actual` is not an escape hatch for product logic. When uncertain, use common code; ask only
when genuinely different product behaviors are required. Generated code, builds, packaging/manifests,
and real adapter tests are placement exceptions, not places to hide behavior.

Implementation order: locate the common owner and platform duplicates; extend common contracts,
controllers and UI; add common behavior/failure tests; compile or test common code for Android,
JVM/Desktop and iOS **before host wiring**; add minimal native delegation. Before committing,
inspect each platform diff separately and move portable code back to Core. The handoff must name
every changed platform production file and its exact native boundary. New host controllers,
repositories, schedulers, state holders, action factories, provider mappings, or policy classes are
presumed violations. Stop and extract discovered violations immediately; unexplained platform
differences are debt. Do not repair a legacy graph scheduled for deletion merely to compile it:
delete/isolate it or document the compile exception in the active migration plan.

## Key Directories

| Directory | Ownership |
| --- | --- |
| `core/domain` | Portable models, capabilities, provider/playback/repository contracts. |
| `core/app` | Shared runtime, lifecycle, queue/playback and Cast/Connect transports/controllers. |
| `core/presentation` | Product composition, state/actions, UI models and service/effect ports. |
| `core/storage` | Shared SQLDelight schema, migrations, repositories and cache persistence. |
| `core/ui` | Shared Compose screens/components, artwork policy and localized resources. |
| `providers/navidrome`, `providers/jellyfin` | Common provider protocols; Navidrome also hosts Subsonic-family/Aurral integration. |
| `apps`, `platforms`, `native` | Executable hosts; native adapters; BASS JNI/rendering implementations. |
| `core/testkit`, `core/tls-testkit` | Reusable adapter contracts and disposable TLS fixtures. |
| `scripts`, `tools/genre-ontology`, `docs` | Build/QA/release helpers; ontology generation; maintained guidance/evidence. |

## Development Commands

Run from the repository root. These examples use POSIX shell; in PowerShell 7 replace `./gradlew`
with `.\gradlew.bat`; quote `-P...` arguments in both PowerShell 7 and 5.1. Make targets require GNU Make and POSIX utilities
(e.g. MSYS2 on Windows); use direct Gradle tasks in native PowerShell.

| Task | Command |
| --- | --- |
| Discover shortcuts | `make help` |
| Android debug build | `./gradlew :apps:android:assembleDebug` |
| Build, stage and launch macOS app | `make macos-test` |
| Stage Windows app | `.\gradlew.bat "-Pnaviamp.bass.platform=windows-x64" :apps:desktop:stageLocalTestApp` |
| Shared/provider/Desktop tests | `make desktop-test` |
| Focus one shared suite | `./gradlew :core:app:jvmTest --tests '*NaviampApplicationRuntimeTest'` |
| Architecture guard | `./gradlew verifyCoreFirstArchitecture` |
| Android unit tests / lint | `./gradlew :apps:android:testDebugUnitTest :apps:android:lintRelease` |
| Complete local non-device gate | `make test` |
| Coverage gate and HTML/XML reports | `make coverage` |
| Linux native/package/launch smoke | `make linux-verify` |
| Validate one translation | `python scripts/validate-translations.py core/ui/src/commonMain/composeResources/values-de/strings.xml` |

`make test` runs `verifyCoreFirstArchitecture`, `:core:storage:verifySqlDelightMigration`,
`:koverVerify`, and Android `testDebugUnitTest`, `testReleaseUnitTest`, `verifyDebugBassNativePackage`
(tasks under `:apps:android`). This covers shared/provider and JVM/Desktop behavior, including
Desktop BASS/JNI loading/decoding (requires CMake and native BASS), plus Android unit tests and
Android native-library packaging. `scripts/summarize-gradle-tests.sh` requires XML reports for
`jvmTest`, `desktopTest`, `testDebugUnitTest`, and `testReleaseUnitTest`; missing reports fail the gate.
This is not equivalent to `./gradlew check`.
`make windows-test` and `make linux-test` stage apps but do not launch them; `macos-test` does.
Use `-Pnaviamp.bass.platform=windows-x64`, `linux-x64`, or `macos-arm64` for the relevant Desktop
native target. See [testing](docs/testing.md) for connected/mobile/native commands and prerequisites.

### Standalone Android car-pad loop (local Windows setup)

This setup runs Naviamp directly on an Android tablet/head unit, not through Android Auto
projection or an Android Automotive OS image. The local AVD is `Naviamp_CarPad_API35`:
Android 15/API 35, 1280x720 landscape at 160 dpi, 8192 MB RAM, and 6 virtual CPUs. Android
Emulator 36.6.11 caps an 8-core request to 6, even with `-cores 8`. Always use the explicit
ADB serial `emulator-5582`; do not install to every connected device.

For shared changes with the same behavior on PC and Android, local PC runtime verification is
sufficient; an AVD installation is not required. Android-specific changes, or an explicit request
to update the AVD, require building the current Android debug app, installing it with
`adb -s emulator-5582 install -r`, launching **Naviamp v2 Test**, and verifying the changed behavior
on that AVD before handoff. Preserve the AVD and app data; use the helper below if needed. Report
any deployment blocker and never claim the AVD is updated when it was not installed.

The ignored `.windows-testing/android-env.ps1` helper reuses Android Studio's JDK 21 and the
installed SDK, setting environment variables only for the current PowerShell process.
`.windows-testing/android` is a local junction to the `Naviamp-CarPad-Dev` directory on drive `Q:`;
it holds the AVD's writable disks and Gradle cache. These files and the AVD are machine-local
prerequisites, not supplied by a fresh clone. Do not commit the helper, AVD, or `local.properties`.

Run from the repository root in PowerShell:

```powershell
. ./.windows-testing/android-env.ps1

# Only when this emulator is not already running; preserves data and disables snapshots.
Start-NaviampCarPad
adb -s emulator-5582 wait-for-device
adb -s emulator-5582 shell getprop sys.boot_completed # Must print 1 before installing.

./gradlew.bat :apps:android:assembleDebug "-Pkotlin.native.enableKlibsCrossCompilation=false"
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }

adb -s emulator-5582 install -r apps/android/build/outputs/apk/debug/android-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'Android installation failed.' }
adb -s emulator-5582 shell am start -n app.naviamp.android.v2test/app.naviamp.android.MainActivity
```

The separate **Naviamp v2 Test** package leaves the normal app untouched. Use the helper for
cold launches: direct Device Manager or bare emulator launches can use snapshots. Stop the AVD
before changing its resources, then cold-start it; never wipe its data just to apply RAM/CPU changes.
Verify live resources with `adb -s emulator-5582 shell getconf _NPROCESSORS_ONLN` and
`adb -s emulator-5582 shell cat /proc/meminfo`; usable guest memory is slightly below configured RAM.

This Play Store tablet image uses SwiftShader here and has shown slow startup, Android-system
boot warnings, and a Google Play Cast-module update forcing an app restart. Verify the rendered
screen and interaction rather than treating an `am start` result as proof. It is a layout/input
stand-in, not evidence of real-car performance, playback, audio routing, or ignition/suspend behavior.
Match the actual head unit's Android version, resolution, and density before making UX conclusions.

## Code Conventions & Common Patterns

- Follow official Kotlin style (`gradle.properties`): four-space indentation, existing multiline
  trailing-comma conventions, PascalCase types, camelCase members. Use neutral/`Naviamp` names in
  Core; reserve `Android`, `Desktop`, and `Ios` prefixes for genuine platform adapters.
- Prefer constructor-injected interfaces/effect ports and explicit service catalogs such as
  `NaviampCoreServices`, not host-owned feature graphs or new service locators.
- Use immutable data-class snapshots and typed sealed/enum states. Keep `MutableStateFlow` private,
  expose `StateFlow` via `asStateFlow()`, and use atomic `update` where needed. Compose observes
  state and dispatches shared actions; it does not duplicate orchestration.
- Use structured coroutines, explicit scope/resource lifetimes, `Mutex` for serialized operations,
  and generation/session guards against stale callbacks. Always rethrow `CancellationException`;
  report ordinary failures and map them to defined error/status state. Do not silently swallow them.
- Preserve provider/source/account/library cache isolation and negotiated capabilities.
- Artist portraits and placeholders must be circular everywhere; albums remain square (existing
  small rounding is allowed). Pass media kind to shared artwork/row components and use the shared
  shape policy; never infer shape from URL, label, or containing page.
- Every new UI string belongs in `core/ui/src/commonMain/composeResources/values/strings.xml`
  and every maintained translation. No hardcoded Kotlin/native UI copy. Release changelog prose
  is English-only and must never enter string resources; About navigation/control labels stay localized.
- Cross-platform settings must round-trip through shared export/import and sync. Cover older-export
  defaults and imported-value normalization; only genuinely device-specific settings stay local.

### UI and wording consistency

- Match equivalent pages with the existing shared components, spacing, typography, icon sizes,
  artwork treatment, and control behavior. Do not introduce a page-specific convention when a
  shared pattern already fits.
- Use the same established terminology and action labels for the same concepts across pages.
  Reuse canonical string resources; do not create separate variants just to add or remove a period.
- Short UI copy has no trailing full stop: titles, headings, buttons, menu items, field labels,
  concise subtitles and setting captions, and terse status or empty-state labels. Examples:
  `Favorite Songs`, `All songs you have marked as favorites`, `Connected`, and `No favorites yet`.
- Detailed explanations, warnings, error-recovery instructions, and multi-sentence messages retain
  normal sentence punctuation, for example `Could not load favorite songs. Try again.` Classify
  copy by its displayed role, not its resource-key suffix, word count, or wrapping at a given width.
- Preserve meaningful punctuation, including progress ellipses, questions, abbreviations, decimal
  points, versions, URLs, and punctuation inside user or provider data. Never strip punctuation at
  runtime or apply a blanket replacement across all strings.
- Review the same copy role across equivalent screens and maintained translations. Respect each
  language's grammar and punctuation; preserve multi-sentence translated explanations. New copy
  still belongs in shared string resources and every maintained translation.
- Verify affected resource formatting and actual rendered text. Keep behavioral, localization,
  accessibility, and formatting coverage; do not add tests whose only purpose is to freeze wording.

### Persistence migrations

Inspect `main`/release history before adding a migration. Consolidate unmerged, unshipped branch
schema changes into the single next migration after that baseline, updating the canonical schema
alongside it. Add another migration only once its predecessor is merged into the release line,
shipped, or an explicit staged sequence is required; local `user_version` is not release evidence.
For drifted development databases, inspect the exact database/objects and minimally repair branch-owned
tables/version markers. Regenerate derived branch data only when safe; never add compatibility
migrations solely for experimental local history or alter unrelated user data to avoid a reset.

### Repository workflow

Follow [development workflow](docs/development-workflow.md): GitHub is canonical; Forgejo is a manual
secondary mirror, never a merge/release source. Issues are optional, not a prerequisite for
implementation. Use existing relevant issues when helpful; do not create upstream issues solely
to track local or fork-only work. Merge complete work through a PR to `main`, linking
related issues when they exist. Use milestones for release scope and short-lived release branches
from accepted `main`. Do not treat unreleased work as available in public documentation.

### Rapidly changing upstream

Upstream develops rapidly. Keep local changes as independent of existing upstream code as practical:
prefer cohesive additions behind existing common contracts and narrow integration points, and
minimize touched files and diffs. Avoid unrelated refactors, file moves, renames, formatting churn,
and dependency changes. Do not duplicate product logic or introduce parallel frameworks just to
avoid edits; extend the existing shared owner when correctness or architecture requires it.
Keep local guidance in `AGENTS2.md`; leave `AGENTS.md` upstream-owned apart from its one-line reference
and explicitly user-requested changes to shared policy.

Upstream merges are a recurring workflow, not a one-time migration. When a conflict exposes tightly
coupled fork behavior, prefer a focused extraction into a fork-owned shared file or class with a narrow
integration hook in the upstream owner. Preserve both sides' behavior and tests; do not duplicate
upstream implementations, move files cosmetically, or discard fork features to make a merge apply.
Keep native adapters limited to platform effects and record any platform verification that cannot run
locally. Resolve semantic conflicts before accepting an automatic merge as complete.

## Important Files

- `settings.gradle.kts`: module graph; `build.gradle.kts`: architecture guard and aggregate Kover gate.
  `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `.java-version`: toolchain pins.
  `Makefile` and `.github/workflows/verify.yml`: local shortcuts and actual CI matrix.
- [NaviampCore.kt](core/presentation/src/commonMain/kotlin/app/naviamp/presentation/NaviampCore.kt)
  and [NaviampCoreServices.kt](core/presentation/src/commonMain/kotlin/app/naviamp/presentation/NaviampCoreServices.kt):
  shared product facade and injected contracts.
- [NaviampApplicationRuntime.kt](core/app/src/commonMain/kotlin/app/naviamp/app/NaviampApplicationRuntime.kt):
  serialized lifecycle/error pattern;
  [StorageCoreRepositoryCatalog.kt](core/storage/src/commonMain/kotlin/app/naviamp/storage/StorageCoreRepositoryCatalog.kt):
  portable persistence graph. Schema/migrations: `core/storage/src/commonMain/sqldelight/app/naviamp/storage`.
- Host entry points: [Android MainActivity](apps/android/src/main/kotlin/app/naviamp/android/MainActivity.kt),
  [Desktop main](apps/desktop/src/desktopMain/kotlin/app/naviamp/desktop/app/Main.kt),
  [iOS composition](apps/ios/src/iosMain/kotlin/app/naviamp/ios/NaviampIosApplication.kt).
- [NaviampSettingsUi.kt](core/ui/src/commonMain/kotlin/app/naviamp/ui/NaviampSettingsUi.kt): shared About changelog;
  `CHANGELOG.md`, `VERSION`, `VERSION_CODE`, `.github/releases`, `.github/RELEASE_TEMPLATE.md`: release inputs.
  `docs/README.md` indexes maintained documentation; prefer it to historical plans.

## Runtime/Tooling Preferences

- Use the committed Gradle Wrapper, not global Gradle or a JavaScript package manager. `.java-version`
  pins JDK 21; README permits 17+ and shared/Android CI uses 17. Prefer the pinned JDK locally and
  follow the target workflow for reproduction. Current pins: Gradle 9.9.1, Kotlin 2.4.21, Compose 1.12.2.
- Android compile SDK is 37, target SDK is 36, and bytecode target is 17. CI installs NDK 27.0.12077973 and
  CMake 3.22.1; these are CI packages, not Gradle NDK pins. Native CMake projects require 3.22+.
  Configure SDK paths through `ANDROID_HOME`/`ANDROID_SDK_ROOT` or ignored `local.properties`.
  Desktop native builds also need the target's BASS libraries; `CMAKE_EXE` overrides CMake discovery.
- Windows/Linux packaging must run on that OS; Windows installers need WiX 3.x. iOS requires
  macOS/Xcode. An unsigned IPA is not a signed/store-published build. `make linux-verify` is Linux
  x86-64 only: requires `java` (JDK 21+), `timeout`, `xvfb-run`, audio/OpenGL development dependencies,
  `libsecret-tools`, and Android SDK API 36 even for Desktop; see `docs/testing.md`.
- Python 3 runs translation/release helpers; shell scripts need a POSIX environment. Do not enable
  Gradle configuration-on-demand: it is unstable with the current multiplatform packaging graph.
  No dedicated ktlint/detekt/Spotless task is configured; Android lint is the documented lint gate.
- On Windows, authenticated `gh` commands must run in the Windows credential context outside the
  workspace sandbox. Check the existing credential there with `gh api user` before any login flow;
  sandbox keyring failures are not grounds to replace or refresh credentials.

## Testing & QA

- Use `kotlin.test` and `kotlinx-coroutines-test` (`runTest`, `runCurrent`, `backgroundScope`, injected
  clocks/dispatchers); prefer local recording fakes. Providers use Ktor MockEngine. Reuse adapter
  contracts from `core/testkit` and disposable mTLS fixtures from `core/tls-testkit`.
- Put shared behavior/failure tests in `commonTest`; use JVM/Desktop, Android instrumentation, and
  iOS simulator/native tests for real boundaries. Shared rendered Compose tests cover JVM and Android.
  Focus a relevant suite first, then the non-device gate; CI adds native Desktop, Android device and
  iOS gates. Do not treat JVM passes as proof of native audio, packaging, UI or accessibility behavior.
- Kover enforces **60% aggregate JVM/Desktop line coverage**, excluding generated SQLDelight/resource classes.
  The root aggregate does not include `:apps:android`; Android app unit tests run separately.
  HTML: `build/reports/kover/html/index.html`. Native/XCTest/instrumentation/C/JNI require explicit
  checks outside that metric. Keep live-provider tests opt-in, credentials in environment variables,
  and fixtures/profiles disposable. Cross-device Connect requires two explicitly selected devices.

### Continuous animations

Preserve smooth motion without sustained material CPU or unrelated parent repainting; disabling
animation, reducing playback updates, or accepting high baseline CPU is not a fix. Measure static,
individual-animation and combined states in a visible real window at identical size, scale, refresh
rate and power conditions. Record process CPU, parent-surface frames and relevant GPU/compositor
cost; confirm actual motion (blank/frozen is failure). Cover idle, paused, hidden/minimized, restored,
resize, input, clipping and accessibility as applicable. Cache unchanged content; use compositor
transforms/clipping. A Compose layer/separate surface alone is not proof. Policy stays common;
native adapters execute presentation effects. Add reproducible probes/shared tests and keep the fix
open until the real app meets its documented budget.

### Release gates

- Compare with the previous public release, lead with major capabilities, then improvements/fixes.
  A first-time platform/feature is one capability, not a list of prerelease implementation repairs.
- Before tagging, update the shared About changelog, its important-entry regression coverage, and
  verify actual About rendering. Outdated/unverified in-app notes block tagging. Audit README and
  the public website's feature/compatibility/download/install pages against accepted source and actual
  distribution; correct stale claims and record evidence in release readiness. Submission is not availability.
- Name every GitHub release exactly `Navo vX.Y.Z`, with no subtitle; verify before and after
  publication. Publish an **Announcements** Discussion for each new pushed release, covering new
  capabilities, changes, fixes and upgrade/compatibility notes; never announce unreleased branches.
- GitHub release entries credit each community contributor's accurate role and link originating
  issues and implementation PRs when available (commit if neither exists), using absolute URLs. Track acknowledgments
  across releases; verify first-time status against prior notes and all contribution history, including
  reports/testing/docs/translations, not just commits or GitHub's first-PR list. Credit only shipped work.
- For community contributions, include consistent, deduplicated **Thanks** in GitHub notes, shared
  About changelog, Discord and Announcements Discussion; welcome first-time acknowledged contributors,
  include reporters/testers, use preferred public handles and honor anonymity. About/Discord retain plain
  change bullets without per-change credits or issue/PR/commit references; add only Thanks. Prepare this
  plain-list version when forwarding to Discord. Detailed links/credits belong in GitHub release notes.
