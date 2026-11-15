# AICP VPN 2.3.28

## Changes
- Add a Global Telegram aggregate feed to Source Finder and Multi-Location; the Android app fetches the published CDN snapshot rather than connecting directly to Telegram.
- Keep Global profiles separate from country attribution and let the existing qualification checks decide which profiles enter the usable Multi-Location pool.
- Add cache-busting and a Raw.Githack fallback for the public feed.
- Harden LAN proxy sharing permission and credential checks, and preserve authenticated accounts when deriving the HTTP inbound.

## Validation
- Android unit tests: 987 passed; 0 failures, 0 errors, 0 skipped.
- Release lint task passed; non-blocking deprecation warnings remain.
- Five signed APK variants built for arm64-v8a, armeabi-v7a, universal, x86, and x86_64.
- APK Signature Scheme v2 and zipalign verification passed for all five variants.
- Package: `com.aicp.vpn`; version `2.3.28` (`versionCode=769`); minimum Android 10 (API 29); target API 37.
- SHA-256 values are in `SHA256SUMS_2.3.28_r1.txt`.

## Operational notes
- The VPS feed timer is enabled and active at a 20-minute interval.
- At the 2026-11-15 18:20 UTC scheduled run, the `proxy_kafee` channel returned no supported proxy URIs. The publisher failed closed and retained the prior 77-profile snapshot. A fresh feed publication is therefore not claimed by this release.
- Feed manifest health remains `not_probed`; profile extraction is not proof that a proxy is live.
- Physical-device installation and live VPN behavior have not yet been independently verified.
