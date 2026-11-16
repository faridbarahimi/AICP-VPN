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


## User-directed source retention and Nova review (follow-up)

- Restored `proxy-kafee-telegram` / `proxy_kafee` as an enabled, retained source in `config/telegram-sources.json`. It is explicitly marked `retention=keep` and `low_frequency_after_days=30`.
- Live Telegram preview at 2026-11-16 10:40 UTC returned 13 visible messages, latest post `2026-11-16T10:15:37Z`, 22 supported proxy URIs. All 22 passed endpoint safety/DNS qualification; 19 accepted TCP connections. It is currently active, not dormant.
- Updated source lifecycle policy: a post older than 30 days is `low_frequency`, not dead. If it still contains safe endpoints, it remains selected. Sources with no configs/messages are excluded only from that run's payload, never deleted from the source catalog, and are checked again on the next run. The effective/rotated catalog preserves every explicitly configured source.
- Nova repository review: `IRNova/Nova-Proxy` README documents a self-hosted Cloudflare Worker panel with per-owner/per-user generated subscription links and auto-subscription formats; the repository describes its Worker source as protected/obfuscated and does not publish a shared public configuration feed. The README links official announcements to `@irnova`, but a live preview at this check exposed no supported public proxy URI; retain as an announcement/reference channel, not a configuration-feed source.
- The supplied `https://irnova.github.io/Nova-Proxy/Htmel/` URL currently returns HTTP 404. The GitHub Pages root `https://irnova.github.io/Nova-Proxy/` returns HTTP 200. The repository's configured homepage currently points at the 404 path.
- `IRNova/Nova-Proxy-App` is a separate Wails v3 / Go desktop client repository describing MITM and GSA Relay components. Its public README/tree does not expose a shared public configuration feed. It is useful as a technical research reference, not a direct feed source.
- No third-party Nova code or binaries were copied into AICP-VPN. The Nova repositories and `@irnova` were not added to the active config feed because they do not currently expose a public shared config list.
- Follow-up validation: 20 focused tests pass; Python compile, publisher shell syntax, and `git diff --check` pass. A dry-run against the live three-source catalog extracted 103 URIs, tested 103 endpoints, accepted 98 as safe, observed 96 TCP-reachable, and selected all three sources including Cafe Proxy. Protocol-level proxy verification remains 0.

## Cafe Proxy restoration — production verification

- Code/config commit: `b42bee3d7d5eeae34f10339b2dbb868e2389b8a3` (`fix(vpn-feed): retain low-frequency Cafe Proxy source`).
- GitHub Actions run `43851022133` completed SUCCESS. Collector tests, bounded source intelligence qualification, and feed collection passed; the workflow's publication step was skipped for the push event as designed. The VPS publisher performed the production snapshot update.
- Production publisher ran successfully at 2026-11-16 10:41:21 UTC (`Result=success`, `ExecMainStatus=0`).
- Public immutable snapshot commit: `390e6ae`; generated at `2026-11-16T10:41:19Z`; `config_count=98`; manifest payload SHA-256 `dcba6eb8394d2188c482d79f06b6f582625e2530085e12a84b99487ddca07ee0`.
- Production report: 114 extracted across all inspected sources, 114 endpoint checks, 102 endpoints passed safety/DNS checks, 105 TCP-reachable; selected-feed sources yielded 103 extracted, 103 tested, 95 TCP-reachable and 98 unique safe profiles after deduplication. `proxy_verified=0` (no protocol-aware session test yet).
- Cafe Proxy is explicitly selected in the production rotation at rank 2: 22 extracted, 22 safety-qualified, 22 TCP-tested, 20 TCP-reachable; latest visible post `2026-11-16T10:15:37Z`. It is not being removed or penalized for low cadence.
- The `config-feed` branch still contains only the latest three files: `feed/telegram-all.txt`, `feed/telegram-all.json`, `feed/telegram-source-intelligence.json`.
- Combined focused tests: 20/20 PASS; Python compilation, publisher shell syntax, and `git diff --check` PASS. Working tree clean after the source/config commit.


## External Nova-inspired source integration (2026-11-16)

- Added `scripts/external_source_intelligence.py` with 16 public subscription
  aggregator URLs and typed network datasets for Cloudflare ranges, CM CIDRs,
  AS13335/209242/24429/199524, Iran IPv4 ranges, Nova WARP endpoints, per-ISP
  clean-IP lists, and Proxifly SOCKS5/HTTP records.
- External inputs are quarantined under `/var/lib/aicp-vpn-source-intelligence/`
  with mode 0600. They are not auto-enabled and raw IP/relay datasets are never
  turned into public subscription profiles.
- Added `scripts/verify_external_configs.py` using pinned sing-box 1.14.4 and
  actual outbound HTTP 204 tests; only VLESS/Trojan TLS with supported
  transports are eligible. Other protocols remain quarantined.
- Added `scripts/merge_external_verified.py`: the publisher merges only
  verified snapshots no older than 30 hours; stale/invalid snapshots are
  excluded. Cafe Proxy remains separately enabled and retained.
- First live run: 11/16 subscription source URLs responded; 9,956 unique safe
  URI candidates; 2,333 VLESS/Trojan TLS candidates; first 200 selected, 179
  TCP-reachable, 179 tested with sing-box, 64 protocol-verified. This is a
  bounded sample, not a statement that all candidates work.
- Dataset results: Cloudflare IPv4 15 CIDRs; CM 68; AS13335 1,108; AS209242 230;
  AS24429 256; AS199524 307; Iran IPv4 1,747; WARP 82 endpoints; Proxifly
  SOCKS5 5,415 and HTTP 4,119 records. Nova clean-IP files had zero usable
  entries/placeholders or were missing; recorded as empty/unavailable.
- Official sing-box ARM64 archive SHA-256 matched the GitHub release API digest;
  binary installed at `/opt/aicp-vpn-tools/sing-box-1.14.4/sing-box`.
- Daily systemd service now enables external collection and protocol
  verification, with AF_NETLINK permitted for sing-box network initialization;
  `TimeoutStartSec=600`. Live service run completed successfully at
  `2026-11-16T11:25:58Z`, with 64 verified profiles and no batch errors.
- Added unit tests for source parsing, source-host restrictions, quarantine
  permissions, and freshness-gated feed merge.

## External-source publication and CI confirmation (2026-11-16 11:30 UTC)

- Production publisher merged the 64 fresh sing-box-verified external profiles
  with the existing Telegram feed. Public snapshot generated at
  `2026-11-16T11:28:41Z`: 159 unique configuration URIs (95 Telegram-derived
  and 64 external protocol-verified), SHA-256
  `fbe88bd7341159da95e4f5a39d41db9aa6fcd89c6945014edbdc7a5f7d263d4a`.
- Public `config-feed` branch commit: `51221d8e9156f9bc55b2c7996e2db386773623e5`.
  Its tree contains only `feed/telegram-all.json`, `feed/telegram-all.txt`, and
  `feed/telegram-source-intelligence.json`.
- Independent public read-back confirmed manifest count = actual URI lines =
  159, all 159 unique, zero unsafe URI entries, and all 64 verified external
  profiles present. Public manifest reports
  `mixed_telegram_tcp_and_external_sing_box_verified` and the external count.
- Code commit `cc18bbe` (`feat(vpn): add verified external source intelligence`)
  and CI coverage commit `5c2af80` (`ci(vpn): cover external source intelligence tests`)
  are pushed to `main`; working tree clean.
- GitHub Actions run `43858243366` for `5c2af80` completed SUCCESS. All jobs
  succeeded; publish step was correctly skipped on push (production publishing
  remains the VPS timer/publisher responsibility).
- Local regression: 29/29 Python unit tests PASS; Python compile, shell syntax,
  `git diff --check`, and systemd unit verification PASS. Both VPS timers remain
  active; latest daily source-intelligence service result SUCCESS.
