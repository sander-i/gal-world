# Gal World

English | [简体中文](README.zh-CN.md)

A self-hosted cloud gaming platform for Windows visual novels (galgames): deploy games to a Linux or
Windows host and play them in a browser. The portal owns the game library, metadata, sessions, media,
saves and operations; the streaming pipeline is handled by Selkies (Linux) or Sunshine (Windows).

**Version**: `0.0.1` (the single source of truth is `<version>` in `portal/pom.xml`; `application.yml`
injects it via `@project.version@`, and no other location repeats it).

Released under the [MIT License](LICENSE). This is a self-built solution that grew out of personal
needs, not a general-purpose commercial product. Self-hosted deployments, issues and PRs are welcome,
but no out-of-the-box or backward-compatibility guarantee is made.

## Screenshots

![Library: company / tag filters and quick start-stop on cards](docs/images/portal-library.png)

| Game detail: info and actions on the left, CG showcase on the right | Game edit: metadata, cover and CG management |
|---|---|
| ![Game detail](docs/images/portal-game.png) | ![Game edit](docs/images/portal-edit.png) |

Screenshots are taken from a locally running portal; games, companies and covers are sample data
(placeholder images, no actual game assets).

## Features

- **Play in the browser**: Selkies 2.1 on Linux (WebRTC/WebSocket), Sunshine + Moonlight Web on Windows
- **Multiple concurrent games**: on Linux every game gets its own wine prefix, its own Xvfb display,
  its own ALSA subdevice, its own audio chain and port
- **Sessions survive disconnects**: closing the browser does not terminate the game session; come back
  and continue
- **Game library portal**: hero recommendations, "for you" picks, company / localization group / tag
  filters, search, quick start-stop on cards
- **Import wizard**: upload a zip / 7z or a whole folder in the browser (chunked, resumable), or pick a
  directory already on the server; it registers the game, initializes the wine prefix, installs Chinese
  fonts and verifies the first launch
- **Metadata**: companies / localization groups (aliases, URLs, descriptions), tags (aliases, colors),
  drag-to-sort, enable/disable per dimension
- **Media management**: covers, icons, promotional CGs (images or videos), multi-image rotation,
  drag-to-sort
- **Image preloading**: derived images generated per display size, the browser picks one via `srcset`,
  showing a fast preview before the sharp version loads
- **Playback experience**: heartbeat timer, idle auto-stop, canvas calibration (auto / manual), launch
  progress, latency display, crash telemetry
- **Playtime tracking**: counted only while the play page is visible (20-second heartbeat), idle time
  not counted
- **Save management and switching**: backups can carry tags and descriptions, any backup can be
  switched in as the current save; a snapshot is taken before switching. Five safety guards (refuse to
  switch while running, snapshot before switching, temporary directory on the same partition,
  rename-and-replace with rollback, archive path validation); importing a game automatically keeps an
  "original" save
- **Game package download**: two-step action on the detail page, first packaging the game directory
  into a zip, then downloading it; excludes the portal-generated `game.env` and `wineprefix/`
- **One-click deploy / full uninstall**: the backend runs preset steps to install or uninstall the
  environment (Linux / Windows), streams logs in real time, and asks for a second confirmation on
  dangerous steps. Uninstall cleans down to "portal still runnable" so switching to another environment
  leaves no residue: on Linux it removes Wine / the Selkies venv / audio and game units / the artifact
  cache, purges xvfb, PulseAudio, python3-venv, Chinese fonts and all i386 packages, and reverts the
  i386 architecture; on Windows it removes Moonlight Web, the Sunshine service and leftover
  directories, the virtual audio driver and the artifact cache. Both platforms keep the portal itself,
  the database, certificates, games and saves; Linux also keeps the JDK and Windows keeps Java
- **Remote update**: Gitee release assets plus a standalone updater, SHA256 verification, service stop
  with backup, health check, rollback on failure
- **Themes**: four built-in themes plus a custom theme editor (stored locally)
- **Config snapshots**: `backup.sh` with health verification; on failure, restore first, then
  investigate
- **Data export**: `GET /api/export` exports games, tags, settings and session counts as JSON

## Architecture

```text
Browser
  │
  ├── HTTPS :8088 ──> Portal (Spring Boot + Vue, single jar)
  │                     ├── H2 file database
  │                     ├── session control ──> systemd user unit / local process
  │                     └── media, saves, backup, update
  │
  └── HTTPS :9002/:9003 ──> Selkies (Linux)
                              │ X11 capture
                           Xvfb :2 / :3
                              │
                           wine (per-game WINEPREFIX)
                              │ ALSA hw:0,0,<slot>
                        snd-aloop virtual sound card
                              │ capture hw:0,1,<slot>
                     PulseAudio source gal_<id>_in
                              │ module-loopback
                     PulseAudio sink gal_<id>
                              │ monitor
                        pcmflux → Opus → browser
```

On Windows the last segment is replaced by Sunshine capturing the desktop and hardware-encoding it
(NVENC / QSV / AMF), with the browser decoding and playing it through Moonlight Web; the portal and the
game run on the same machine, and the game is a native exe that does not go through Wine.

**Tech stack**

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.3.5, Spring Data JPA, H2 file mode |
| Frontend | Vue 3.5, Vue Router 4, Element Plus 2.14, Vite 6 |
| Linux streaming | Ubuntu 24.04, Selkies 2.1, Wine 9.0, PulseAudio 16.1, Xvfb, snd-aloop |
| Windows streaming | Sunshine, Moonlight Web, VB-Audio Virtual Cable |
| Encoding | no GPU on the server: llvmpipe software rendering + x264 software encoding; hardware encoding on Windows |

## Directory layout

```text
portal/
├── src/main/java/io/github/sander_i/   backend: web / service / domain / platform / deploy / update
├── src/test/java/                backend tests
└── frontend/                     Vue 3 frontend source (build output goes to src/main/resources/static/)

deploy/
├── bin/                          server-side scripts (run-game.sh, galworld CLI, backup.sh, updater.sh, ...)
├── systemd/                      systemd user units
├── win/                          Windows deploy scripts (install / galworld / update-portal / uninstall)
├── openbox/                      window manager config (alternative, currently disabled)
├── portal-deploy.sh              build locally → upload jar → restart → health check
└── release.sh                    build and publish a Gitee Release

docs/
├── ENVIRONMENT.md                environment probing and Windows deployment
├── GAME-IMPORT-PLAN.md           game import design
└── *.md                          other design and optimization documents

tools/
├── artifacts/                    third-party artifact manifest and sync script (Nexus + mirror fallback + SHA256)
└── diagnostics/                  one-off troubleshooting scripts (per-hop audio checks, cold-boot simulation, ...)

preview/                          static UI prototype (plain HTML, no build required)
demo/                             demo assets
deploy.sh                         Linux one-shot bootstrap (system deps → JDK → cert → service → health check)
uninstall.sh                      full uninstall script (removes the portal, data, games and privilege entry too)
```

## Getting started

### Requirements

| Item | Requirement |
|---|---|
| JDK | 17+ (backend compiles to 17) |
| Node | 20+ (frontend build) |
| Maven | 3.9+ |
| Linux | Ubuntu 22.04+ / 24.04, Wine 9.0, PulseAudio, snd-aloop, Xvfb, Selkies |
| Windows | Windows 10 1903+ / 11 (x64), Sunshine, Moonlight Web, virtual audio cable |
| Permissions | `loginctl enable-linger` on Linux; one-time admin install on Windows |

### Build

```bash
# Build the frontend into portal/src/main/resources/static/
cd portal/frontend
npm ci
npm run build

# Build the single jar
cd ..
mvn -DskipTests package
# Artifact: portal/target/gal-world-portal-<version>.jar (version comes from portal/pom.xml)
```

### Linux deployment

```bash
git clone https://gitee.com/sander-o/gal-world.git
cd gal-world

# deploy.sh finds the jar under portal/target automatically; use --jar to point at one
./deploy.sh
```

`deploy.sh` covers one-time base-environment setup: system packages (apt) → JDK → directories → jar →
self-signed certificate → systemd user units → linger → firewall → health check. It is idempotent, and
anything already installed is skipped. System packages are only installed automatically on
Debian/Ubuntu; other distributions get a notice and skip that step, and if you already prepared the
environment yourself you can skip it with `--skip-deps`. Middleware such as Wine / Selkies / the audio
chain is still installed from the backend's "Environment info → One-click deploy".

Open `https://<host>:8088/`. `deploy.sh` writes a fixed deploy token into the systemd unit and prints it
when the script finishes (it persists across restarts):

```bash
部署令牌: 7f3c...
```

To set your own, use `--token <token>` or the `GALWORLD_DEPLOY_TOKEN` environment variable. Only when
you start the jar manually, bypassing `deploy.sh`, is the token generated randomly and printed solely
to the startup log:

```bash
journalctl --user -u galworld-portal -n 200 --no-pager | grep '随机令牌'
```

Enter it on the "Admin" page to unlock environment info, one-click deploy and remote update.

If no artifact repository (Nexus) address is given, only `127.0.0.1:8081` on the local machine is
probed; if `manifest.json` can be fetched it is written into the unit, otherwise a warning is printed at
the end. Use `--artifact-nexus` or `GALWORLD_ARTIFACT_CANDIDATES` to specify one.

If Nexus needs credentials, use `--artifact-user` + `--artifact-pass`, or write the password to a file
and pass `--artifact-pass-file <path>`. Credentials are stored in `~/.config/galworld/artifacts.env`
(0600), read by systemd via `EnvironmentFile`, so later reruns do not need them again.

`deploy.sh` also installs a fixed privilege entry `/usr/local/sbin/galworld-sudo` and a matching
`/etc/sudoers.d/galworld-deploy` allowlist (only these six subcommands: packages / snd-aloop /
audio-group / linger / uninstall-sys / uninstall-packages), so the backend's "one-click deploy" does not
need a sudo password typed into the page. To skip it, pass `--no-sudoers` and fall back to entering the
password in the page.

### Full uninstall

```bash
./uninstall.sh --dry-run   # first see what would be deleted
./uninstall.sh             # run it; requires typing "销毁" to confirm
```

Unlike the backend's "uninstall environment": the page action only cleans down to "portal still
runnable" (for switching environments), while `uninstall.sh` destroys the portal too — units,
`~/galworld` (including games and saves), `~/.config/galworld`, uploaded jars/scripts, system runtime
packages and the i386 architecture, linger, ufw rules, the privilege entry and the sudoers allowlist.
Irreversible.

`--keep-data` keeps `data/` and `games/`, `--keep-packages` skips system package removal, and `--yes`
skips the interactive confirmation. Once cleanup is complete the script deletes itself; if residue
remains it is kept so you can rerun and investigate.

### Windows deployment

```powershell
# Administrator PowerShell
.\deploy\win\install.ps1 -Jar ..\portal\target\gal-world-portal-<version>.jar
.\deploy\win\galworld.ps1 add sample-game --name "Sample Game" --exe "D:\Games\sample-game\game.exe"
```

Prerequisites and pairing steps for Sunshine, Moonlight Web and the virtual audio cable are in
[`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md).

### Development

```bash
# Backend on :8080 (the frontend dev proxy points here)
cd portal
GALWORLD_PORTAL_PORT=8080 mvn spring-boot:run

# In another terminal, frontend on :5173
cd portal/frontend
npm ci
npm run dev
```

Tests:

```bash
cd portal && mvn test
cd portal/frontend && npm test && npm run check:theme
```

## Portal

| Path | Content |
|---|---|
| `/` | Library: hero recommendations, "for you" picks, company / localization group / tag filters, search |
| `/games` | All games |
| `/games/import` | Import wizard: upload an archive / folder or choose a server directory |
| `/game/<id>` | Detail: start-stop / restart, playtime, metadata, cover and icon, save management and switching, game package download |
| `/game/<id>/edit` | Edit: name, canvas, company / localization group / tags, description, cover / icon / CG, rotation rules |
| `/play/<id>` | Play: iframe streaming, heartbeat timer, canvas calibration, telemetry |
| `/settings` | Appearance themes, feature flags, switching mode, canvas calibration, idle stop, CG audio, rotation, token, about |
| `/admin` | Access token, upload limits, image preloading optimization, environment info and one-click deploy, version and update check |
| `/companies` | Company maintenance |
| `/localization-groups` | Localization group maintenance |
| `/tags` | Tag maintenance |

**HTTPS is required**: the play page embeds Selkies in an iframe, and the Selkies client uses WebCodecs,
which requires `window.isSecureContext === true`. An HTTPS iframe embedded in an HTTP parent page is
still judged a non-secure context.

### Playtime accounting

Time is counted only while the **play page is visible** (20-second heartbeat). Switching to another tab,
minimizing the window or leaving the page stops the timer, and it settles automatically within 45
seconds. Idle time therefore does not count.

### Canvas calibration

Wine negotiates the window size from the game itself, and sometimes it hits the canvas edge and the
picture gets cropped. When the play page loads (auto mode), or when you click "Calibrate resolution" in
the top-right corner (manual mode), it reads the window rectangle from `xwininfo` and adjusts
`WIDTH/HEIGHT` to roughly `window + 24`, up to 4 rounds, taking the canvas with the largest window area.
Global settings → "Canvas calibration" toggles auto / manual.

### Media and rotation

Each game can have several covers, one icon and multiple promotional CGs (images or short videos).
Covers and CGs each have their own order / random rotation and interval; CGs can be set to auto-advance,
loop, or stay on the current one. Videos are muted by default, which can be changed to sound-on in the
settings. Images get two derived sizes, 640 / 1024 (the resolution is not reduced when the source is
1024 or smaller), and the browser picks one by display size. Default limits: 8 covers, 5 CGs, 8 MB
images, 120 MB videos, adjustable under "Admin → Upload limits".

## Configuration

Common environment variables (full list in `portal/src/main/resources/application.yml`):

| Variable | Default | Description |
|---|---|---|
| `GALWORLD_ROOT` | `~/galworld` | Installation root (games / data / lib) |
| `GALWORLD_PORTAL_PORT` | `8088` | Portal port |
| `GALWORLD_TLS` | `false` | Enable HTTPS; `deploy.sh` sets it to `true` |
| `GALWORLD_KEYSTORE` | empty | PKCS12 certificate path |
| `GALWORLD_KEYSTORE_PASSWORD` | `changeit` | Certificate password |
| `GALWORLD_KEY_ALIAS` | `galworld` | Certificate alias |
| `GALWORLD_STREAM_URL` | empty | Fixed Moonlight Web stream URL on Windows |
| `GALWORLD_WRITE_TOKEN` | empty | Write API token; empty means no check |
| `GALWORLD_DEPLOY_TOKEN` | empty | Deploy / admin token; when empty a random one is generated at startup and logged |
| `GALWORLD_DEPLOY_CIDRS` | `127.0.0.1/32,192.168.0.0/16,10.0.0.0/8` | Networks allowed to call deploy endpoints |
| `GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD` | `false` | Whether the page may accept a sudo password |
| `GALWORLD_UPDATE_ENABLED` | `true` | Whether remote update is allowed |
| `GALWORLD_UPDATE_ALLOW_OVERRIDE` | `false` | Let the admin page specify a tag / version override (including downgrade) |
| `GALWORLD_UPDATE_API_BASE` | `https://gitee.com/api/v5/repos` | Release API base for the update source; point at a mirror or self-hosted instance when the default is unreachable |
| `GALWORLD_UPDATE_REPO` | `sander-o/gal-world` | Update source repository path `owner/repo` |
| `GALWORLD_UPDATE_WEB_BASE` | `https://gitee.com` | Web base of the update source repository, used to build release page links |
| `GALWORLD_UPDATE_TOKEN` | empty | Read-only token for private repositories / mirrors; the Linux updater can also read `~/.config/galworld-update.env` |
| `GALWORLD_ARTIFACT_NEXUS` | local Nexus | Artifact download source for one-click deploy |
| `GALWORLD_ARTIFACT_USER` / `GALWORLD_ARTIFACT_PASS` | empty | Artifact repository credentials; empty reads anonymously, configure for private repositories |

Settings live in the H2 `setting` table and are changed from the settings / admin pages.

## Deployment and update

```bash
# Build on the development machine and push to the server (SSH fallback path)
bash deploy/portal-deploy.sh
bash deploy/portal-deploy.sh --build-only   # build only

# Build and publish a Gitee Release
GITEE_TOKEN=<token> bash deploy/release.sh
bash deploy/release.sh --dry-run            # build and produce dist/ only, do not publish
```

From the portal's "Admin → Version and check for updates" you can check for and perform a remote
update. The updater is a separate process: download → SHA256 / zip / version verification → stop the
service → back up the jar and H2 → atomic replace → start → health smoke test → automatic rollback on
failure.

With `GALWORLD_UPDATE_ALLOW_OVERRIDE=true` the admin page shows "override update", letting you force any
published Release tag or version (for example `v<version>-pr7`), including downgrades and reinstalling
the same version. Off by default. The target version must ship the update endpoint itself; the updater
checks the jar before stopping the service and rejects versions without it.

Branch verification flow: `GALWORLD_RELEASE_TAG=v<version>-pr7 bash deploy/release.sh` publishes a
branch build, then enter that tag in the admin page's override install.

**The update source is configurable.** "Admin → Remote update source" can change the release API base,
repository path and repository web base, or you can set `GALWORLD_UPDATE_API_BASE` /
`GALWORLD_UPDATE_REPO` / `GALWORLD_UPDATE_WEB_BASE` directly. The API only needs to be compatible with
the GitHub / Gitee Release API (`/repos/{owner}/{repo}/releases`), so when GitHub is unreachable you
can point it at Gitee, a self-hosted Gitea / GitLab or a mirror. Changes take effect immediately; the
updater process reads the same address from `releaseApi` in `update-request.json`, with no restart or
separate environment variable needed. For private repositories the read-only token goes through
`GALWORLD_UPDATE_TOKEN` (the Linux updater also accepts `GITEE_TOKEN=` or `UPDATE_TOKEN=` in
`~/.config/galworld-update.env`, and the file must be mode 600).

Note: an in-portal update only replaces the jar and H2, not `~/galworld/bin/*`. When the updater scripts
themselves change, after upgrading run "Deploy portal operational scripts" under "Admin → One-click
deploy", which writes `updater.sh` / `backup.sh` / `galworld` / `gen-stream-cert.sh` back out from the
jar; alternatively run `bash deploy/portal-deploy.sh` on the development machine. Otherwise the server
keeps running the old scripts.

Windows is at parity with Linux: download and verify the jar → stop the service → back up the jar and
H2 → atomic replace → start → version smoke test → automatic rollback on failure. The updater script
ships with the jar and is released to `data\update\` at runtime; the manual usage
`deploy\win\update-portal.ps1 -Jar <jar>` is retained.

## Self-check and recovery

```bash
~/galworld/bin/selfcheck.sh      # full-stack self-check
~/galworld/bin/backup.sh backup  # config snapshot (with health verification)
~/galworld/bin/backup.sh restore # run this first when troubleshooting

bash tools/diagnostics/m4-check.sh   # portal acceptance check (run in the repository, not installed by deployment)
```

`selfcheck.sh` / `backup.sh` / the `galworld` CLI are installed from the jar into `~/galworld/bin/` by
the "Deploy audio scripts" and "Deploy portal operational scripts" steps under "One-click deploy"; if
you prefer not to open the page, `bash deploy/portal-deploy.sh` also pushes `deploy/bin/*` up.

Operational runbooks are internal documents and are not distributed with the repository.

## Known limitations

| Item | Status |
|---|---|
| Image quality | Rendered at 4:3 under Wine, below native 16:9 on Windows; no GPU on the server, so software rendering + software encoding |
| Window title bar | Wine's self-drawn 30px cannot be removed; mitigated by centering |
| Concurrency | about 1–2 cores of encoding per stream; 2 streams stable in a 32-thread test |
| HTTPS | The portal must use HTTPS, otherwise the play page is black and reports `This application requires a secure connection` |
| Remote update | Supported on both Linux and Windows; Windows has no journal-equivalent check, so the smoke test only covers the version and three HTTP endpoints |
| Platform differences | Linux uses a dedicated port and audio chain per game; Windows shares one Moonlight Web entry |

## Documentation

- [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md): environment probing, Linux / Windows deployment
- [`docs/GAME-IMPORT-PLAN.md`](docs/GAME-IMPORT-PLAN.md): game import wizard design
- [`CONTRIBUTING.md`](CONTRIBUTING.md): development environment, build and test, commit conventions
- [`SECURITY.md`](SECURITY.md): security model, defaults and vulnerability reporting
- [`CHANGELOG.md`](CHANGELOG.md): version history

Operational runbooks, one-click deploy execution details and remote update field-test records are
internal documents and are not distributed with the repository.

## License

Released under the [MIT License](LICENSE), copyright sander.

The copyright of game content, localization patches and in-game assets belongs to the original authors
and localization groups and is not included in this repository. Third-party components distributed with
the release jar (Spring, Hibernate, Logback, H2, Vue, Element Plus, etc.) and their full license texts
are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and [`licenses/`](licenses/); both are
also packed into the jar's `META-INF/` and provided with the Release assets.

Components fetched from upstream by deployment scripts and run as separate processes (Sunshine,
Moonlight Web, Wine, Selkies, VB-Cable) follow their own licenses and are not linked into this project's
code. The project's artifact mirror is for the deployer's own intranet use only and is not offered for
public download.

---

When modifying this project with an AI coding agent (Claude Code / Codex, etc.), read
[`AGENTS.md`](AGENTS.md) first: it describes the architectural boundaries, the single source of truth
for the version number, and which scripts change system state.
