# Themes

Appearance controls live directly in **Settings → Appearance**, on both standard and TV surfaces.
Choose **Light**, **Dark**, or **System**, independently of the selected theme. The bundled choices
are labeled **Naviamp** and **Forest**. Dark remains the default for older settings. System follows live operating-system
appearance changes; explicit Light and Dark ignore them.

Themes are bundled definitions, not downloadable or user-installed files. There is no theme-file
import/export UI or native theme-document adapter. General settings backup, restore, sync, and
Connect provisioning still preserve `InterfaceSettings.theme.mode` and `selectedPackId`. A theme ID
unavailable in the receiving build falls back to Naviamp without changing the selected mode.

## Player Background

The inline **Player Background** section in Appearance selects **Aurora**, **Album Blur**, or
**Single Color** for both the expanded player and the mini-player bar. It is not a submenu and no
longer lives under Experience. TV Appearance exposes the same controls; the compact TV player card
also uses the selected background.

Aurora automatically uses its Light or Dark treatment from the resolved theme's `isDark` flag;
there is no separate tone selector. It keeps its color-count and gradient-angle controls. Album
Blur lightens the artwork in Light mode and darkens it in Dark mode, while keeping its blur-radius
control. Both update when the active theme changes, including live OS changes in System mode,
without requiring a new track or artwork load. Single Color keeps its 6×6 color palette and paints
the selected color unchanged in either mode.

The remaining `InterfaceSettings` fields retain settings export/import/sync behavior; changing
style does not reset the other styles' stored values. Old exports' `auroraTone` values are ignored
and no longer exported. The theme's mode and selected pack determine the tone on each device.

Navigation and browsing surfaces remain theme-solid. Both player sizes use the existing shared
background renderer and artwork cache. Mini-player background layers are clipped to the bar/card
bounds, and finite palette transitions run in the background composition rather than its controls.
Single Color paints the selected color directly without an unused color tween.

Main navigation destinations and drawer open/close buttons never show hover tooltips, so they
cannot cover neighboring navigation controls. Their labels and accessible names remain available;
the desktop tooltip preference still controls tooltips elsewhere.

## Shared ownership

- `core/domain/src/commonMain/kotlin/app/naviamp/domain/settings/NaviampTheme.kt` defines palettes,
  packs, the immutable catalog, preferences, and the pure mode resolver.
- `core/domain/src/commonMain/kotlin/app/naviamp/domain/settings/NaviampForestTheme.kt` is a complete,
  self-contained example. It does not inherit colors from another theme.
- `core/ui/src/commonMain/kotlin/app/naviamp/ui/NaviampTheme.kt` converts the resolved palette into
  `NaviampColors` and Material3 roles. Composition locals supply the same theme to screens, menus,
  dialogs, and separately mounted diagnostics content. Typography continues to use existing font
  size preferences.
- `core/ui/src/commonMain/kotlin/app/naviamp/ui/NaviampThemesSettings.kt` contains the shared
  Appearance controls, including the inline Player Background section. Hosts neither select themes
  nor interpret their definitions.
- `core/ui/src/commonMain/kotlin/app/naviamp/ui/NaviampAppBackground.kt` owns the shared background
  model/rendering used by expanded players and compact player bars.

Catalog validation happens once at construction. The catalog snapshots its registrations, exposes
an immutable list, and rejects duplicate IDs or a missing/modified Naviamp fallback. Rendering
memoizes palette conversion by theme preference and OS appearance; themes add no periodic polling
or animation loops.

## Definition contract

A `NaviampThemePack` contains:

| Field | Contract |
| --- | --- |
| `schemaVersion` | Exactly `1`. This versions the bundled definition, not the general settings document. |
| `id` | Stable, unique, case-sensitive `[a-z][a-z0-9-]{0,63}`. `naviamp` identifies the unchanged fallback. |
| `name` | Nonblank, at most 80 characters, no control characters. |
| `author` | Optional nonblank attribution, at most 120 characters, no control characters. |
| `light`, `dark` | Complete `NaviampThemePalette` values; every token is required. |

Color values are opaque `#RRGGBB` strings; hex digits may use either case. The required
`playerSurface` token additionally accepts `null`, explicitly meaning transparent. Other color
tokens cannot be null. Definitions contain no code execution hooks, URLs, external assets, fonts,
widget overrides, or references to other themes.
Kotlin type checking enforces required tokens; catalog validation checks version, identity,
metadata, and color syntax. It does not certify accessibility: authors must verify contrast.

| Palette tokens | Purpose |
| --- | --- |
| `background` | Main app background and TV reading surface. |
| `backgroundWarm`, `backgroundOlive` | Artwork/player gradient fallback colors. |
| `primaryText`, `secondaryText`, `mutedText` | Foreground hierarchy. |
| `border` | Outlines and separators. |
| `accent`, `onAccent` | Selected/action background and its foreground. |
| `controlSurface` | Navigation, app cards, controls, dialogs and lyrics panels. Naviamp Dark uses `#393E46`. |
| `playerSurface` | Standard full-player controller, queue/history/related/station panels and unselected rows; null means transparent. |
| `playerSecondaryText`, `playerMutedText` | Shared full/mini-player metadata hierarchy, independent of ordinary app text. |
| `albumArtPlaceholder` | Missing/loading artwork. |
| `favorite` | Favorite indicator. |
| `menuSurface`, `menuText` | Menu background and foreground. |
| `error`, `onError` | Destructive/error action pair. |
| `errorContainer`, `onErrorContainer` | Error message panel pair. |
| `success`, `onSuccess` | Positive action pair. |

### Material3 mapping

No role is left on a default Material purple palette:

- Primary, primary-container, inverse-primary, primary-fixed and primary-fixed-dim use `accent`;
  their foreground/fixed-variant roles use `onAccent`.
- Secondary and secondary-fixed/dim use `accent`, with `onAccent` foregrounds. Secondary-container
  uses `controlSurface` and `primaryText`.
- Tertiary and tertiary-fixed/dim use `success`, with `onSuccess` foregrounds. Tertiary-container
  uses `controlSurface` and `primaryText`.
- Background/on-background use `background`/`primaryText`.
- Surface/on-surface use `controlSurface`/`primaryText`; surface-variant/on-surface-variant use
  `controlSurface`/`secondaryText`; surface-tint uses `accent`.
- Inverse-surface/inverse-on-surface use `primaryText`/`background`.
- Error and error-container roles use their matching four palette tokens.
- Outline and outline-variant use `border`.
- Surface-bright and surface-container-high use `controlSurface`; surface-container-highest uses
  `menuSurface`. Surface-dim, surface-container, surface-container-low and surface-container-lowest
  use `background`.
- Modal scrim remains black. Transparent presentation surfaces remain transparent.

## Adding or generating a theme

1. Create a common-domain definition beside `NaviampForestTheme.kt`, with its own stable ID and
   **all twenty-three tokens in both variants**. Copy the complete example structure, not a runtime
   inheritance/override chain.
2. Register the definition in `NaviampThemes` in `NaviampTheme.kt`. Registration order is UI order.
   Never remove or mutate the Naviamp fallback.
3. Add the bundled theme's display-name resource to every maintained string catalog and resolve
   it in the shared Appearance controls. Do not put product labels in platform files.
4. Extend theme contrast/rendering coverage for the new definition. Verify body text at least
   4.5:1 and relevant controls/played waveform at least 3:1. Check selected, disabled, destructive,
   error, menu, placeholder, and focused states in both variants.
5. Exercise selection, System changes, restart persistence, general settings round-trip, and the
   unknown-ID fallback. A new theme should require no host changes.

Artwork color extraction is separate from application theming. Fixed black artwork fades use the
selected pack's dark foreground palette. Naviamp sets `playerSurface = null` in both variants, so
its controller and queue panels reveal the selected Player Background. Forest retains its opaque
player surfaces. Selected-row/tab feedback, lyrics panels, menus and dialogs keep their existing
fills. This token does not determine the mini-player fill: Player Background does.

Full and mini players share `playerSecondaryText` and `playerMutedText`. Naviamp Light uses
`#272C35` for secondary player text and `#353C47` for muted player text; its ordinary app
`secondaryText` (`#4F5663`) and `mutedText` (`#727A86`) stay unchanged. Naviamp Dark and both Forest
variants retain their previous player text colors. Navigation, browsing, settings, and Material
roles do not adopt the player-specific tokens.

Player titles and elapsed/total time still use `primaryText`. Artists, mini-player subtitles
(including the empty state), and full-player secondary metadata use `playerSecondaryText`; album
labels retain 84% opacity. Queue durations and muted metadata use `playerMutedText`. The mini-player
currently has no muted metadata. Filled artwork uses the selected pack's dark player text tokens
on its fixed black fade, even in Light mode.

The full-player play/pause circle uses opaque theme `accent` with an `onAccent` icon. The volume
slider's active track and thumb ring use that same accent, matching selected navigation buttons.
Filled artwork keeps the resolved app accent pair rather than switching to the dark artwork
palette's accent. Backgrounds, waveform colors, and mini-player transport styling are unchanged.

The darker Naviamp Light player text meets 4.5:1 contrast on the tested pale rose, sand, and blue
Aurora regions, including the translucent album label. Transparent panels and user-selected
backgrounds can still reduce contrast, particularly with dark artwork regions or custom Single
Color choices. Aurora's tone and Album Blur's overlays do not guarantee contrast for every palette.

TV promotes secondary/muted text to the selected primary foreground and keeps its separate opaque
ten-foot reading background. Visualizer artwork, intentional white artwork labels and modal
dimming are not recolored as ordinary app chrome.

## Native boundary inventory

| Production file | Irreducible native boundary |
| --- | --- |
| `apps/android/src/main/kotlin/app/naviamp/android/MainActivity.kt` | Applies shared icon-contrast facts through Android `enableEdgeToEdge`/`SystemBarStyle`. |
| `apps/android/src/main/res/values/styles.xml` | Android startup system-bar fallback; runtime shared appearance is authoritative. |
| `apps/desktop/src/desktopMain/kotlin/app/naviamp/desktop/app/Main.kt` | AWT/Compose native window icon setup; removes independent OS-only titlebar selection. |
| `apps/desktop/src/desktopMain/kotlin/app/naviamp/desktop/app/DesktopNaviampCoreHost.kt` | Applies shared effective appearance to the owned AWT window through the existing native titlebar adapter. |
| `apps/desktop/src/desktopMain/kotlin/app/naviamp/desktop/app/DesktopStatsForNerdsWindow.kt` | Owns a secondary native window and applies the captured app theme/chrome to it. |
| `apps/ios/src/iosMain/kotlin/app/naviamp/ios/IosThemeViewController.kt` | UIKit child-controller containment, full-bounds layout, and status-bar style invalidation. |
| `apps/ios/src/iosMain/kotlin/app/naviamp/ios/NaviampIosApplication.kt` | Creates the Compose/UIKit root and forwards shared status-icon facts to the native container. |
| `apps/ios/iosApp/NaviampApp.swift` | UIApplication/scene/window lifetime; retains one process-owned application across scene disconnect/reconnect. |
| `apps/ios/iosApp/NaviampRootView.swift` | Retains the shared application/controller and guards native resource cleanup against duplicate termination. |

The iOS UIKit container is the actual window root, not a child of a SwiftUI hosting controller.
It changes `preferredStatusBarStyle`, never `overrideUserInterfaceStyle`, so System observation
cannot feed back from the app's own override. Scene disconnect releases only the window, not
background playback or the shared runtime. Compose owns safe-area handling; its child view fills
the container bounds.

## Verification

Focused domain, settings-storage/sync/Connect, shared UI, TV contrast/focus, swipe, diagnostics, and
application-shell regression suites cover this feature. The setup regression checks rail, bottom,
and drawer viewports, stale routes, connecting/errors, saved-profile restoration, and last-profile
removal. Before any local or remote connection exists, only connection setup is rendered; saved
profiles and active remote playback retain the normal shell.

Local validation includes Android debug packaging, Windows packaged-app staging, shared iOS Arm64
klib compilation, a standalone typecheck of the UIKit status-bar container, translation validation,
and the architecture guard. Rendered player tests verify transparent/opaque panels, live style
changes with and without media, both player sizes, and Album Blur clipping at route/navigation
seams. Small-viewport settings tests exercise unscaled slider geometry, keyboard focus/stepping,
and all three inline styles. Real Android UI checks cover direct Appearance, bundled theme/mode changes,
Player Background on both surfaces, system-bar contrast, and the requested icons/copy. A captured Windows app window verifies
the connection-only first-run surface and native titlebar polarity.

Theme-driven player backgrounds additionally have common color-policy and legacy-settings import
coverage, rendered full/mini-player theme transitions with unchanged artwork, live System-mode
changes for both bundled packs, and standard/TV tone-selector removal checks. A visible Windows
shared-UI probe verified both treatments and missing-artwork fallbacks before and after the change.
Shared UI/presentation compiled for JVM, Android and iOS Arm64 klibs; this is not iOS runtime proof.

Full iOS application linking, Swift/UIKit compilation and on-device status-bar, rotation, reconnect,
and background-playback runtime checks require macOS/Xcode and were not performed on Windows.
