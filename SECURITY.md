# Security Policy

English | [简体中文](SECURITY.zh-CN.md)

## Security model

Gal World is a **single-user, self-hosted tool**. Its design assumption is "runs only on your own
machine or intranet, operated by you", not a multi-tenant service. Some defaults therefore look loose:

| Setting | Default | Notes |
|---|---|---|
| `galworld.write.token` | **empty = no check** | All non-GET `/api/**` requests need header `X-GalWorld-Write-Token`; when empty, writes are not checked |
| `galworld.deploy.token` | empty | Generated randomly at startup and printed to the log; used by `/api/deploy/**` and `/api/admin/**` |
| `galworld.deploy.allowed-cidrs` | `127.0.0.1/32,192.168.0.0/16,10.0.0.0/8` | Deploy endpoints only accept these source networks |
| `galworld.deploy.allow-sudo-password` | `false` | Whether the page may accept a sudo password |
| `server.ssl.key-store-password` | `changeit` | Local self-signed certificate only; **must be changed when exposed** |
| H2 database | user `sa`, empty password | File database under `~/galworld/data/portal/`, protected by filesystem permissions |

**Do not expose the portal port directly to the public internet.** For remote access, use a VPN or an
authenticated reverse proxy, and at minimum set `GALWORLD_WRITE_TOKEN` and `GALWORLD_DEPLOY_TOKEN` and
narrow `GALWORLD_DEPLOY_CIDRS`. After deployment, run `~/galworld/bin/selfcheck.sh` to see the
effective values.

Other notes:

- **Artifact downloads**: the local Nexus (`127.0.0.1:8081`) is preferred; on failure it falls back to
  third-party proxies such as `gh-proxy.com` / `ghfast.top`, always with SHA256 verification. If you do
  not trust third-party proxies, clear `GALWORLD_ARTIFACT_MIRRORS` and use only your own Nexus.
- **Metadata sources**: the update manifest and release assets come from the endpoint configured under
  "Remote update source" in the admin page (defaults from `GALWORLD_UPDATE_API_BASE` /
  `GALWORLD_UPDATE_REPO` / `GALWORLD_UPDATE_WEB_BASE`) and are parsed by `ReleaseClient`. Change the
  hosting platform or mirror in the admin page, not in source code; all downloads are SHA256-verified.
- **External components**: Wine, Selkies, Sunshine, Moonlight Web and VB-Cable are installed on the
  target machine by the deployment flow and run as separate processes; they are not linked into this
  project. Report their security issues upstream.

## Supported versions

Security fixes are provided only for the latest release (see the Releases page); historical tags are
not patched.

## Reporting a vulnerability

**Do not post exploitable details or a PoC in a public issue.**

1. Open an issue whose title starts with `[SECURITY]` and whose body only says that you found a
   security problem and would like to discuss it privately. Do not include reproduction steps, impact
   or any credentials;
2. The maintainer will reply with a private channel for further details.

<!-- Optional before publishing: add your security contact email here for reporters who cannot open an issue -->

Please also state: affected version, deployment form (Linux / Windows), and whether it reproduces under
default configuration. Defaults rely on the "single-user intranet" assumption; if the issue only
applies to a deployment hardened as described above, please say so explicitly.

## Out of scope

- Actions that require local administrator rights, or that require existing read/write access to
  `~/galworld/`;
- Vulnerabilities in upstream components (Wine, Selkies, Sunshine, Moonlight, VirtualBox / drivers,
  etc.);
- "Write authentication is off by default" as such — that is the documented design trade-off, and the
  hardening steps are above;
- Missing rate limiting, CAPTCHA and similar public-internet protections — this project does not target
  public deployment.
