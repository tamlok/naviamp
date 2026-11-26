# Manual Flatpak tooling

This fork has **no configured public Flatpak remote, installer endpoint, or signing identity**.
Flatpak automation is disabled; its source is retained at `.github/workflows/flatpak.yml.disabled`.
Only Windows and Android pipelines are active. These local Linux tools do not establish publication
or availability. Current source and standalone releases are at
[tamlok/naviamp](https://github.com/tamlok/naviamp) and its
[release page](https://github.com/tamlok/naviamp/releases).

`prepare.py` reads `APPLICATION_NAME`, `APPLICATION_ID` and `RELEASE_REPOSITORY` from the repository
root, exporting the name as `APP_NAME` for the Python publisher and verifier. It requires an existing
immutable, published stable release from that fork with the exact release page, Linux asset download
URL, and SHA-256 digest. It refuses mutable/draft/prerelease versions, ambiguous assets, and
upstream or foreign releases. There is no upstream-image fallback or relabelling. Current supported
pipelines do not produce Linux release images, so an eligible fork Linux release must already exist
before these manual packaging steps can be used. The Java runtime and native libraries come from
that same image. This is binary packaging, not a Flathub source-build submission.

The accepted archive is `Navo-vVERSION-linux-x64-release.zip` for tag `vVERSION`; old-name archives
are not accepted. Its top-level `Navo/` directory contains the actual executable `bin/Navo` plus the
bundled runtime and libraries. Packaging copies that directory into the unchanged `/app/lib/naviamp`
location. The Flatpak command remains `naviamp`, whose launcher directly executes
`/app/lib/naviamp/bin/Navo`; no old-executable alias is installed.

The retained runtime is Freedesktop 25.08; review its support window before use. The tooling targets
x86_64. Java/Compose uses X11, including XWayland; native Wayland is not claimed.

## Build and publish

Publication requires an operator-selected HTTPS OSTree repository URL, its full uppercase signing
fingerprint, and the independently accepted armored public key. There are no inherited endpoint or
key defaults. `--repository-url` is the exact update-remote URL ending in `/`; the scripts do not
append `repo/`. Host the generated `public/repo` directory at that URL. Do not use `--no-gpg-verify`.

The private signing home must remain separate from the built repository and all public/asset output.
Keep it on the operator's computer with mode 0700, never in source, Actions secrets, artifacts, logs,
or a published directory. Keep encrypted offline backups of the key and revocation certificate.
Reuse an accepted identity for later updates; plan key rotation with a signed client transition.

On Linux, install Flatpak/flatpak-builder, GnuPG, AppStream and zstd, plus Freedesktop Platform and SDK
25.08 from Flathub. Set the following variables to actual trusted inputs and select an eligible
existing fork release; the commands fail if any input is missing:

```sh
: "${APPLICATION_TAG:?Select an existing immutable stable fork Linux release}"
: "${FLATPAK_REPOSITORY_URL:?Provide the actual HTTPS OSTree update-remote URL}"
: "${FLATPAK_SIGNING_FINGERPRINT:?Provide the accepted full uppercase fingerprint}"
: "${FLATPAK_PUBLIC_KEY:?Provide the independently accepted armored public key path}"
: "${FLATPAK_SIGNING_HOME:?Provide the private signing home path}"
app_id=$(cat APPLICATION_ID)
release_repository=$(cat RELEASE_REPOSITORY)
gh api "repos/$release_repository/releases/tags/$APPLICATION_TAG" > release.json
python3 scripts/flatpak/prepare.py --release-json release.json --output build/flatpak-input
flatpak-builder --user --force-clean --repo=build/flatpak-repo \
  build/flatpak-app "build/flatpak-input/$app_id.json"
python3 scripts/flatpak/publish.py --repo build/flatpak-repo --release-json release.json \
  --signing "$FLATPAK_SIGNING_HOME" --public build/flatpak-public --assets build/flatpak-assets \
  --repository-url "$FLATPAK_REPOSITORY_URL" --signing-fingerprint "$FLATPAK_SIGNING_FINGERPRINT" \
  --accepted-public-key "$FLATPAK_PUBLIC_KEY"
python3 scripts/flatpak/verify_public.py --public build/flatpak-public \
  --source build/flatpak-assets/source.json --release-json release.json \
  --repository-url "$FLATPAK_REPOSITORY_URL" --signing-fingerprint "$FLATPAK_SIGNING_FINGERPRINT" \
  --accepted-public-key "$FLATPAK_PUBLIC_KEY"
```

`publish.py` only prepares local output; it never deploys. Both output directories must be pristine.
`verify_public.py` checks descriptors against the explicit remote, fingerprint and accepted key,
rejects substituted provenance, unexpected public files and symlinks, and enforces the size limit.
Generated homepage/source/release links point to the actual fork, not an imagined store or Flatpak
publication site. Retain `source.json`, package digests and acceptance evidence. The OSTree repository
retains two parent revisions and static deltas; the immutable archive preserves objects for the next
publication. Public output must stay below 900 MiB, leaving headroom under GitHub Pages' 1 GiB limit
if the operator chooses that hosting service. Any external upload, hosting and release publication
is a separate, explicitly authorized operator action; none is configured here.

Public output contains `Navo.flatpakref`, `navo.flatpakrepo`, `navo.asc`, `repo/`, `index.html` and
`.nojekyll`. Release assets include the same descriptors and armored key, the binary key `navo.gpg`,
`Navo-vVERSION-x86_64.flatpak`, `source.json`, `repository.tar.zst`, `pages.tar.zst` and `SHA256SUMS`.
The generated installation command uses `flatpak install --user --from ./Navo.flatpakref`; the run
command is `flatpak run cn.com.letalk.navo`. Filenames do not establish trust: independently
accept the signing fingerprint and key before installing or passing `navo.asc` to the smoke script.

## Sandbox and acceptance

Network supports remote libraries and LAN discovery. X11/IPC and DRI support the existing renderer;
PulseAudio supports native playback. Secret Service and
`org.mpris.MediaPlayer2.cn.com.letalk.navo` are the only extra session-bus permissions. Downloads is
the only host filesystem permission. `.local/share/cn.com.letalk.navo` is persisted inside Flatpak's
private namespace; settings use its XDG config directory. The app/desktop/icon identity is
`cn.com.letalk.navo`, while its display name is Navo. This final ID creates a fresh installation,
private-data and credential namespace. Previous local `cn.com.letalk.naviamp` builds, upstream and
non-Flatpak private data are neither read nor automatically migrated or modified. Use shared settings
export/import for a deliberate migration, reconnect accounts, and select accessible download storage.

The runtime supplies the portal-backed desktop tools. The manifest builds the existing `secret-tool`
effect from checksum-pinned libsecret source; no credential policy is duplicated. Linux's in-app
update action only opens release information; Flatpak owns installation and updates to read-only `/app`.
Close and reopen the app after updating. Software-center update scheduling follows the user's settings.

Run `scripts/flatpak/smoke.sh` under a private D-Bus session and X11 display in a fresh test profile.
Its three required positional arguments are the local signed repository, fresh evidence directory,
and explicitly accepted public key path. It has no default signing key and installs only the fork ID.

Before publication, install from a signature-verified remote, confirm actual readable UI, native audio
clock, credentials across restart, menus/input, file selection, MPRIS, and persistent settings/database.
Test a real older application version to the new one and reject an untrusted signing key. Check the
deployed HTTPS remote with the exact public installation command before advertising availability.
Keep known Linux performance/accessibility investigations explicit; this packaging is not a performance
fix. Record desktop/runtime versions and any untested hardware/desktop combinations honestly.
