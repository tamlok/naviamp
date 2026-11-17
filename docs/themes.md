# Themes

Appearance controls live directly in **Settings → Appearance**, on both standard and TV surfaces.
Choose **Light**, **Dark**, or **System**, independently of the selected theme. The bundled choices
are labeled **Naviamp** and **Forest**. Dark remains the default for older settings. System follows live operating-system
appearance changes; explicit Light and Dark ignore them.

Themes are bundled definitions, not downloadable or user-installed files. There is no theme-file
import/export UI or native theme-document adapter. General settings backup, restore, sync, and
Connect provisioning still preserve `InterfaceSettings.theme.mode` and `selectedPackId`. A theme ID
unavailable in the receiving build falls back to Naviamp without changing the selected mode.

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
  Appearance controls. Hosts neither select themes nor interpret their definitions.

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

Colors are opaque `#RRGGBB` strings. Hex digits may use either case. Definitions contain no code
execution hooks, URLs, external assets, fonts, widget overrides, or references to other themes.
Kotlin type checking enforces required tokens; catalog validation checks version, identity,
metadata, and color syntax. It does not certify accessibility: authors must verify contrast.

| Palette tokens | Purpose |
| --- | --- |
| `background` | Main app background and TV reading surface. |
| `backgroundWarm`, `backgroundOlive` | Artwork/player gradient fallback colors. |
| `primaryText`, `secondaryText`, `mutedText` | Foreground hierarchy. |
| `border` | Outlines and separators. |
| `accent`, `onAccent` | Selected/action background and its foreground. |
| `controlSurface` | Cards, controls, and readable player panels. |
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
   **all twenty tokens in both variants**. Copy the complete example structure, not a runtime
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
selected pack's dark foreground palette; ordinary player panels use the selected application
surface. TV promotes secondary/muted text to the selected primary foreground and provides an
opaque reading background, preserving readability over arbitrary artwork. Visualizer artwork,
intentional white artwork labels, and modal dimming are not recolored as ordinary app chrome.

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
and the architecture guard. Real Android UI checks cover direct Appearance, bundled theme/mode
changes, system-bar contrast, and the requested icons/copy. A captured Windows app window verifies
the connection-only first-run surface and native titlebar polarity.

Full iOS application linking, Swift/UIKit compilation and on-device status-bar, rotation, reconnect,
and background-playback runtime checks require macOS/Xcode and were not performed on Windows.
