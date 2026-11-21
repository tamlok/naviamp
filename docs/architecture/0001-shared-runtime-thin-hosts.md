# ADR 0001: Shared Runtime and Thin Platform Hosts

- **Status:** Accepted
- **Date:** 2026-07-16
- **Target:** Naviamp 2.0.0

## Context

Naviamp already shares domain rules, provider behavior, storage schemas, UI models, and much of its Compose UI. Android and Desktop nevertheless construct separate application state, controllers, actions, and lifecycle effects. The previous iOS scaffold followed the same pattern by starting a third platform runtime, leaving playback and several product flows incomplete.

Maintaining three application implementations would make feature parity a recurring manual task. It would also place iOS-specific concerns into product logic and make Android's service-owned playback lifecycle difficult to reason about.

Naviamp needs one product implementation while preserving genuine operating-system differences, existing Android background playback, Desktop packaging, and the final requirement that all platforms use BASS.

## Decision

Naviamp 2.0 will use one shared application runtime and shared Compose application entry point, hosted by thin Android, Desktop, and iOS applications.

### Core-first invariant

Naviamp Core is the product. Platform hosts are providers of operating-system services to that product; they are not separate implementations of Naviamp.

Every normal product feature starts in common code. Its state, commands, policy, validation, navigation, menus, and Compose presentation are defined once and are inherited by Android, Desktop, and iOS. Adding a normal UI element, menu item, action, or ability must not require parallel product edits in each host.

A host may add, remove, or alter behavior only when a concrete operating-system or platform API requires it. That exception must use a narrow shared platform-service contract or capability and must be documented with the specific constraint. A missing host adapter, inconvenient source lookup, unfinished controller, or different existing code layout is implementation debt—not a platform capability and not permission to diverge.

Code review must reject new platform-specific product state, menus, navigation policy, validation, or action interpretation unless the change identifies the genuine platform constraint. When equivalent Android, Desktop, and iOS plumbing begins to grow, the work must stop and move the ownership or composition into common core.

The shared runtime owns:

- application and session state;
- navigation state and restoration;
- provider connection coordination;
- media, playlist, radio, search, download, and settings actions;
- queue and playback policy;
- shared lifecycle decisions and user-facing errors;
- construction of shared UI state and actions.

Each platform host owns only operating-system integration and constructs implementations of narrow shared contracts. Examples include playback devices, media sessions and remote commands, secure secrets, database drivers, filesystem locations, connectivity, notifications, permissions, file selection, application lifecycle, window management, and distribution/update integration.

The composition boundary will use explicit dependency construction initially. A dependency-injection framework will be introduced only if concrete lifecycle or graph-management needs justify it.

### Page navigation

`NaviampNavigationController` owns one in-memory history of typed page descriptors. The shared
presentation controller publishes the visible route and restores media through the ordinary Core
selection commands; it never restores snapshots of playback, settings, providers, or the full shell.
Media identity includes both kind and ID. Refreshing the same destination does not add a Back step.

| Page family | Navigation policy |
| --- | --- |
| Home, Playlists, Library, Radio, My | Selecting a primary destination starts a new history. |
| Downloads, Settings | My tabs replace the root; their existing Back-to-My behavior remains. |
| Search and all five mix builders | Nested routes push the opening page and expose a shared Back control. |
| Album, artist, playlist details | One mixed history restores the selected item and its actual opening page. |
| Home collections | History records the section ID; unavailable sections are skipped on return. |
| Provider discovery | History records a source-scoped surface boundary. Internal browser, artist and release history stays with the discovery controller, along with live request state. |
| Expanded player | Opening/closing preserves browsing history; a page opened from the player records whether Back should reopen it. |
| Settings subpages, connection/account forms, dialogs and drawers | Existing shared local Back/dismiss handlers run before page history. TV retains its focus and preview behavior. |

Header, system and mouse Back follow the visible page; mouse Back uses the shared dispatcher so
dialogs and drawers close first. Phone and TV resolve retained Home subpages in their respective
render order. TV Settings restores its background route without adding a history entry.

Selection commands prepare navigation before deferred loading. Navigation changes invalidate queued
selection tokens and late detail responses, preventing Back or a root switch from reopening a departed
page. Source changes clear page history; discovery returns also validate their source generation.
Process restoration starts a fresh history rather than persisting this transient stack.


### Cross-platform naming

Files and types that implement the same architectural role must use parallel, role-specific names so their relationship is apparent in search results and directory views. Use a neutral or `Naviamp` name for the shared contract/owner and a platform prefix for a genuine host implementation of that role. For example, a shared `NaviampAppShellActionAdapter` may have `AndroidAppShellActionAdapter`, `DesktopAppShellActionAdapter`, and `IosAppShellActionAdapter` counterparts.

Parallel names are reserved for parallel responsibilities. A platform execution adapter must not be named as though it were another implementation of shared product policy, and unrelated files must not be made superficially symmetrical. Prefer precise role names such as `ActionAdapter`, `PlaybackExecutor`, or `CredentialProtector` over broad names such as `UIFunctions`.

Where the target architecture removes a platform-specific product factory entirely, do not spend migration effort merely renaming it into symmetry. First move product construction to the common composition root; then consistently name only the narrow host adapters that remain. New platform files must follow this convention immediately, and the iOS host must mirror established Android/Desktop adapter names whenever it implements the same shared contract.

Android playback may continue to be owned by a foreground service. The Activity and service must communicate through a shared session/command boundary so the shared product runtime does not require Activity lifetime.

iOS may first implement `PlaybackEngine` with AVPlayer to prove streaming, background audio, interruptions, route changes, Control Center, and Now Playing integration. That implementation is temporary. BASS is the required normal iOS playback engine for Naviamp 2.0.0.

## Consequences

### Positive

- Product behavior is implemented once and is shared by default.
- A common feature appears on every host without per-host product wiring.
- iOS becomes a platform-adapter project rather than a separate application rewrite.
- Android and Desktop divergence becomes visible through explicit contracts.
- Shared runtime tests can verify behavior without launching an operating system UI.
- Platform capabilities can be surfaced honestly and consistently.
- BASS-specific advanced playback remains available behind existing playback capability interfaces.

### Costs and risks

- Existing Android and Desktop composition roots must be decomposed incrementally.
- Android's service lifecycle requires a deliberate session boundary; forcing it into a UI-owned runtime would cause regressions.
- Desktop settings and credential storage need separation and security hardening.
- Storage construction and some persistence behavior currently live in platform applications.
- Apple targets will expose JVM assumptions in otherwise shared modules.
- A long-lived migration branch requires disciplined checklist updates and frequent pushes.

## Rejected Alternatives

### Maintain three independent applications

Rejected because navigation, controllers, settings behavior, and feature wiring would drift across platforms.

### Merge and continue the old iOS scaffold

Rejected because it is incomplete, substantially diverged from current `main`, and establishes a separate iOS runtime rather than the chosen shared-runtime boundary. It may be inspected for isolated build or bridge lessons.

### Rewrite the existing applications at once

Rejected because Android service playback and mature Desktop behavior need continuous regression protection. Migration will be performed in behavior slices.

### Use AVPlayer as the final iOS engine

Rejected as the final target because Naviamp requires BASS performance and feature parity. AVPlayer remains useful as a temporary integration proof of concept.

### Move platform APIs into common code through operating-system checks

Rejected because it hides platform coupling, makes common tests weaker, and does not produce replaceable platform services.

## Validation

This decision is successfully implemented when:

- Android and Desktop launch the same shared application runtime;
- iOS launches that runtime through a minimal SwiftUI/UIKit shell;
- platform hosts contain operating-system integration rather than duplicate product controllers;
- shared runtime behavior is covered by platform-independent tests;
- Android service-owned playback survives Activity recreation;
- BASS is the normal playback engine on Android, Desktop, and iOS;
- all platform differences are explicit capabilities or documented unsupported behavior;
- adding a normal shared UI element, menu item, or action requires no Android-, Desktop-, or iOS-specific product implementation;
- every remaining platform difference names the concrete OS/API constraint and is expressed through a narrow common contract.
