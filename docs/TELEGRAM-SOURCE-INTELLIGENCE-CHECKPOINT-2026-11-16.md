# Telegram Source Intelligence & Rotation — Checkpoint 2026-11-16

## Delivered

- Independent source intelligence engine: `scripts/telegram_source_intelligence.py`.
- Public candidate discovery through GitHub repository search and public README link extraction; candidate channel previews are fetched and qualified before being retained.
- Per-source active/inactive/stale/no-config/error classification, post freshness, extraction counts, TCP reachability, latency, ranking and append-only JSONL history.
- Endpoint safety checks reject malformed, localhost/private/reserved and non-public DNS results. DNS answers are vetted and the exact vetted socket address is used for TCP tests.
- URI deduplication ignores display fragments. Discovered channels remain disabled and cannot enter the public feed automatically.
- Rotation plan orders already-enabled sources by measured health and excludes stale/inactive/no-config/unsafe-only sources. A fetch error on any enabled source or an empty selection blocks publication and preserves the previous snapshot.
- The public feed is generated from the selected enabled sources only, using the exact validated URI set from the qualification pass; disabled discovered candidates are not included.
- Public `config-feed` remains a force-replaced, single-root latest snapshot containing only `telegram-all.txt`, `telegram-all.json`, and `telegram-source-intelligence.json`. History and candidate registry remain on the VPS under `/var/lib/aicp-vpn-source-intelligence/`.

## Live qualification evidence

Observed on `instance-ubuntu-aicp` at 2026-11-16 10:27:59 UTC:

| Metric | Result |
|---|---:|
| Telegram sources inspected | 7 |
| Active sources (including disabled candidate) | 3 |
| Inactive discovered candidates | 4 |
| Stale sources | 0 |
| URIs extracted across all inspected sources | 92 |
| Endpoints tested | 92 |
| Endpoints passing endpoint safety/DNS qualification | 89 |
| TCP-reachable endpoints | 86 |
| Protocol-verified proxy sessions | 0 — not yet implemented |
| Unique safe URIs across all inspected sources | 89 |
| Enabled sources selected for feed | 2 |
| URIs extracted from selected feed sources | 81 |
| Selected-feed endpoints TCP-tested | 81 |
| Selected-feed TCP-reachable endpoints | 76 |
| Unique safe URIs in generated feed | 78 |

Ranked sources:

1. `MARAMBASHI`: 61 extracted, 61 safe, 60 TCP-reachable; enabled.
2. `v2ray_free_conf`: 20 extracted, 18 safe, 16 TCP-reachable; enabled.
3. `persianvpnhub`: 11 extracted, 11 safe, 10 TCP-reachable; discovered candidate, intentionally disabled.
4. `CONFIG_V2RAY_VIP`, `Goodbaye_filtering`, `mohamaadfg`, `yawhackka`: no visible messages; classified inactive and disabled.

The five candidate names were discovered from public GitHub repository READMEs. Only `persianvpnhub` currently exposes supported configs; it has not been enabled or published.

## Operational state

- `aicp-vpn-source-intelligence.service`: latest run SUCCESS, exit 0, 2026-11-16 10:27:59 UTC.
- `aicp-vpn-source-intelligence.timer`: enabled and active; next scheduled run 2026-11-17 03:18:59 UTC.
- Existing `aicp-vpn-telegram-feed.timer`: remains enabled and active at its 20-minute cadence.
- Local source history: `/var/lib/aicp-vpn-source-intelligence/source-history.jsonl`.
- Candidate registry: `/var/lib/aicp-vpn-source-intelligence/discovered-sources.json`.
- Latest report: `/var/lib/aicp-vpn-source-intelligence/latest-report.json`.
- Latest report SHA-256 at checkpoint: `17374a35e15bd2d5766fccce0b6b8d07de2cbd3252ea944f1b1d33f07cedccb4`.
- Source history SHA-256 at checkpoint: `06c1b6619d69bfe810eefb00e19e8c3cfd61e704bf1ebe3263fa9a91319102b8`.

## Validation and limitations

- Focused combined unit tests: 18 passed.
- Python compilation: PASS.
- Publisher shell syntax: PASS.
- Workflow YAML parse: PASS.
- `git diff --check`: PASS.
- TCP reachability is not a working proxy session. `proxy_verified=0` because no protocol-aware client handshake/authenticated request adapter is present on this headless VPS. Do not label TCP-reachable endpoints as fully working VPNs.
- GitHub Actions has not yet run against this commit at checkpoint creation. The new public snapshot has not yet been force-published by the updated publisher at checkpoint creation.

## Post-checkpoint deployment verification

- Implementation commit pushed to `main`: `d1c96a5487b2f34e9391b40add0e740bee15922d`.
- Follow-up persistence fix pushed: `71f15c92dfe96c91cf15c226a3b352bb4406049a` (publisher now keeps `/var/lib/aicp-vpn-source-intelligence/latest-report.json` synchronized with each 20-minute publication).
- GitHub Actions run `43849196684` for the implementation commit completed SUCCESS; unit tests, source intelligence qualification, and feed collection steps passed. Its conditional manual-publish step was skipped as expected for a push event.
- Updated publisher ran on VPS successfully at 2026-11-16 10:29:56 UTC (`Result=success`, `ExecMainStatus=0`).
- Public `config-feed` Snapshot was force-replaced and verified from raw GitHub: generated `2026-11-16T10:29:53Z`, 78 URI lines, `config_count=78`, payload SHA-256 `130f29b56a91af34ed22ce194f0296ddd46bde491d94a703a4a30d3d361c2a1d`, `health_status=tcp_reachability_probed`.
- A subsequent public manifest read measured 92 extracted, 92 tested, 87 TCP-reachable across all scanned sources, 77 TCP-reachable within the selected feed, and 0 protocol-verified sessions. Small changes in TCP counts between runs are expected because endpoints are volatile.
- Public snapshot files: `feed/telegram-all.txt`, `feed/telegram-all.json`, `feed/telegram-source-intelligence.json`; the publisher force-pushes a fresh orphan snapshot, so older snapshot contents are not retained on `config-feed`.
- Local latest report SHA-256 after persistence fix: `da09601b11100888397c7083dca7a48839d3facc29d699e8a12b1da21d1d4981`.
- Local append-only history SHA-256 after publisher run: `4b2c94f673aee34c8ff1d65a7c1dbec9c8978b8e94380b1daa40ff4d7bfde156`.
- Follow-up GitHub Actions run `43849339573` for commit `71f15c92dfe96c91cf15c226a3b352bb4406049a` also completed SUCCESS; the test, source-intelligence, and feed-collection steps passed. The workflow's manual-publish step was skipped for the push event; the VPS systemd publisher performed the production publish.
- The `config-feed` branch head is `729bad54e44eddcb45cfa3d039a4e28bd5d94bc0`. Its tree was independently inspected and contains exactly four paths: `feed/`, `feed/telegram-all.txt`, `feed/telegram-all.json`, and `feed/telegram-source-intelligence.json`.
- The immutable commit-pinned GitHub manifest and jsDelivr manifest both resolve to the 2026-11-16 10:29:53Z snapshot with 78 profiles and matching payload checksum. A mutable raw.githubusercontent.com request briefly returned an older cached response; commit-pinned GitHub and jsDelivr confirmed the new snapshot. The app's existing CDN mirror can therefore serve the new snapshot.
- Documentation evidence update committed as `4044d188e4f0f69b25b3e2f117790d8b3db462cd`; working tree clean at that checkpoint.
