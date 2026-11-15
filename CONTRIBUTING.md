# Contributing

English | [简体中文](CONTRIBUTING.zh-CN.md)

A note on what this project is, to save misunderstandings later:

Gal World is a self-built solution grown out of personal needs. It is **not a general-purpose
product**: it does not promise out-of-the-box operation or backward compatibility, and not every
platform combination has been verified by anyone. Issues and PRs are welcome, but the maintainer
prioritizes scenarios they actually use.

## What to report

- Bugs on **an environment you got running yourself**, with reproduction steps, logs and version;
- Documentation errors, broken commands, dead links;
- Platform adaptations (distribution differences, GPU / sound card combinations, Wine behavior);
- Changes that make deployment more robust and rollback-friendly.

## Before you open an issue

1. Search existing issues — self-hosted projects get many duplicates;
2. Check the known limitations in [`docs/OPERATIONS.md`](docs/OPERATIONS.md) (image quality,
   concurrency, HTTPS, and other stated boundaries); security design trade-offs are in
   [`SECURITY.md`](SECURITY.md).

## Development environment

| Item | Requirement |
|---|---|
| JDK | 17+ (backend compiles to 17) |
| Maven | Optional; the repository ships a Maven Wrapper (`./mvnw`) that downloads on first run |
| Node | 20+ (needed only to build the frontend) |

Running tests **does not** need game files, Wine or a graphical environment: backend tests use mocks,
frontend tests are plain Node unit tests.

## Build and test

```bash
# Everything at once: backend tests + frontend tests + theme gate (recommended; CI uses this)
bash tools/ci.sh

# Individually
cd portal && ../mvnw -B test                    # backend tests
cd portal/frontend && npm ci && npm test        # frontend unit tests
cd portal/frontend && npm run check:theme       # theme variable gate
```

Packaging (builds the frontend first, then the jar):

```bash
cd portal/frontend && npm ci && npm run build
cd .. && ../mvnw -DskipTests package            # artifact: portal/target/gal-world-portal-<version>.jar
```

## Read before changing code

- Architecture in [`README.md`](README.md): how the portal and the streaming pipeline split work;
- [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md): platform differences and dependencies;
- [`AGENTS.md`](AGENTS.md): architectural boundaries and hard rules (single source of truth for the
  version, per-game resource isolation, five save-restore guards, frontend build output never
  committed, third-party dependencies must be registered). It is written for AI coding agents, but the
  rules bind humans too.

The easiest ones to trip over:

1. **Write the version only in `<version>` of `portal/pom.xml`** — never hardcode it in scripts or docs;
2. **Do not hardcode versions or internal addresses**; use environment variables (list at the top of
   `deploy.sh`);
3. **New frontend pages must use theme variables only**; hardcoded colors fail `check:theme`;
4. **New third-party dependencies must be mirrored into `THIRD-PARTY-NOTICES.md` and `licenses/`** —
   the build-time `license-maven-plugin` only generates a report and will not fail on omissions, so
   manual review is required;
5. **Never commit game files, localization patches or game assets**: copyright belongs to the original
   authors and localization groups; this repository holds platform code only.

## Commits and PRs

- One commit does one thing; the message explains **why**, not just what changed;
- In the PR description, include: reproduction / motivation, the verification you actually ran (paste
  `tools/ci.sh` or `mvn test` output), and the risks of changes that touch system state;
- When changing `deploy.sh`, `deploy/win/*.ps1`, `uninstall.sh` and similar system-state scripts,
  state which OS and version you tested on;
- Do not relax `minFromVersion`, skip SHA256 verification or disable authentication just to make
  something pass.

## License

The project is released under [MIT](LICENSE). Contributions are handled as "inbound = outbound":
**submitting a contribution means you agree it is distributed with this project under the MIT
License**. No CLA is required, but make sure the code is your own or comes from a source compatible
with this project's license.
