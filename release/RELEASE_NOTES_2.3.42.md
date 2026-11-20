# AICP VPN 2.3.42

## Reliability and scan safety
- Physical-network reachability now has a bounded HTTPS control-host fallback for mobile carriers that do not expose Android's `NET_CAPABILITY_VALIDATED` capability.
- The probe is bound to the selected non-VPN `Network`; it does not use the process default route for its control-host requests.
- Scan cleanup shares the physical-network check across manual scans, public-config refresh and subscription refresh.
- A successful peer in the same batch can confirm that individual `-1 ms` profiles are unreachable. If the entire batch fails, deletion remains conservative: each profile needs two completed failure confirmations and independent physical-network reachability.
- Timeouts (`-3`), untested (`0`/null) profiles and the active tunnel profile remain protected from reachability-based deletion.

## Build and verification
- Android package: `com.aicp.vpn`
- Version: `2.3.42` (`versionCode=783`)
- Minimum Android: API 29; target API 37.
- Five release variants were built: `arm64-v8a`, `armeabi-v7a`, `universal`, `x86` and `x86_64`.
- All five variants passed APK Signature Scheme v2 verification with the pinned AICP release certificate and passed `zipalign -c -v 4`.
- Full JVM unit suite: 1,077 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleRelease` and `:app:lintVitalRelease`: PASS.

## Acceptance boundary
- The distributable artifact in this release folder is the ARM64 APK.
- Physical-device acceptance (installation, live VPN traffic, DNS/IPv6 leak checks, real carrier failover and RTL rendering) has not been performed. A successful build and automated tests do not establish operational acceptance.
