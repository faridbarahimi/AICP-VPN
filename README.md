# AICP VPN for Android

AICP VPN is an Android VPN client based on the open-source v2rayNG project, with AICP-specific connection management, configuration-source handling, Smart Connect selection, Xray health recovery and a refined Android UI.

> **Project status:** Version `2.3.42` is built and signed from canonical source commit `fa3fd1ceb6c615c4f713fd53e979a66d4d3c2178`. GitHub release publication is being completed. Physical-device acceptance (live VPN traffic, DNS/IPv6 leak checks, real failover timing, per-app routing and RTL Persian rendering) remains pending. Do not interpret a successful build as proof of operational acceptance.

## Latest release

**[AICP VPN 2.3.42 — GitHub Releases](https://github.com/faridbarahimi/AICP-VPN/releases/latest)**

The release page provides five signed APK variants:

- `arm64-v8a` — most modern 64-bit ARM Android phones/tablets.
- `armeabi-v7a` — 32-bit ARM Android devices.
- `x86_64` — 64-bit Intel/AMD Android devices and emulators.
- `x86` — 32-bit Intel Android devices/emulators.
- `universal` — all supported ABIs in one larger APK.

Verify downloaded files against `SHA256SUMS_2.3.42_r1.txt` attached to the release and the official AICP signing certificate fingerprint documented in the release manifest.

## Canonical source for 2.3.42

The 2.3.42 APKs were built from `faridbarahimi/AICP-Core-Platform` at commit `fa3fd1ceb6c615c4f713fd53e979a66d4d3c2178`. This repository is the public release/distribution surface; the monorepo path `apps/aicp-vpn/V2rayNG` is the authoritative source for that release.

## Build requirements

- Android SDK with API 37 and Build Tools 37.0.0. The project also pins its native runtime and Xray AAR inputs; see the provisioning commands below.
- JDK 17.
- Gradle wrapper included in this repository.
- Network access to the repositories declared in `settings.gradle.kts`.

Provision the pinned binary build inputs first. The native bundle is downloaded from this repository's `v2.3.27` release and verified against `native-runtime-assets.sha256`; the Xray AAR is fetched from its pinned upstream release and verified against `xray-aar.lock.properties`:

```bash
./scripts/fetch-pinned-native-runtime-assets.sh
./scripts/fetch-pinned-xray-aar.sh
```

The signed Psiphon server-list snapshot and publication stamp are included in `app/src/main/assets/`.

Run unit tests and release lint:

```bash
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintRelease \
  --no-daemon --no-parallel --max-workers=1
```

Build release APK variants:

```bash
./gradlew --dependency-verification=strict \
  :app:assembleRelease -PaicpVersion=2.3.28 \
  --no-daemon --no-parallel --max-workers=1
```

> Reproducibility note: the build commands above describe the historical source snapshot in this distribution repository. The 2.3.42 binaries were built from the authoritative monorepo source at commit `fa3fd1ceb6c615c4f713fd53e979a66d4d3c2178`; use that source tree to reproduce this release.

### Release signing

Release signing is optional for local development. To sign release APKs with an authorized AICP release keystore, provide these environment variables in the build environment:

- `AICP_RELEASE_STORE_FILE`
- `AICP_RELEASE_STORE_PASSWORD`
- `AICP_RELEASE_KEY_ALIAS`
- `AICP_RELEASE_KEY_PASSWORD`

Never commit keystores, signing passwords, API tokens, or local environment files. Without the signing variables, Gradle may produce unsigned release APKs; do not distribute them as official AICP builds.

## Version information

- Application ID: `com.aicp.vpn`
- Version name: `2.3.42`
- Version code: `783`
- Minimum Android: 10 (API 29)
- Target Android API: 37

## Documentation

- [`release/RELEASE_NOTES.txt`](release/RELEASE_NOTES.txt)
- [`release/RELEASE_NOTES_2.3.42.md`](release/RELEASE_NOTES_2.3.42.md)
- [`release/RELEASE_MANIFEST_2.3.42_r1.txt`](release/RELEASE_MANIFEST_2.3.42_r1.txt)
- [`release/SHA256SUMS_2.3.42_r1.txt`](release/SHA256SUMS_2.3.42_r1.txt)
- [`release/RELEASE_MANIFEST.txt`](release/RELEASE_MANIFEST.txt)
- [`release/GOOGLE_PLAY_RELEASE_CHECKLIST.md`](release/GOOGLE_PLAY_RELEASE_CHECKLIST.md)
- [`docs/release/AICP-VPN-2.3.27-CHECKPOINT.md`](docs/release/AICP-VPN-2.3.27-CHECKPOINT.md)
- [`NOTICE.md`](NOTICE.md) and [`LICENSE`](LICENSE)

## License and attribution

This repository is a modified v2rayNG derivative. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md) for GPL-3.0 attribution and bundled third-party notices. The AICP VPN fork is not the official v2rayNG project.
