# AICP VPN Telegram Config Engine

## Architecture

A separate collector fetches configured public Telegram previews, extracts only supported VLESS, VMess, Trojan, Shadowsocks, Hysteria2/Hy2 and TUIC URIs, and deduplicates by URI without its fragment.

The Android app never fetches Telegram directly. It consumes the country-mixed snapshot from:

- Text feed: https://cdn.jsdelivr.net/gh/faridbarahimi/AICP-VPN@config-feed/feed/telegram-all.txt
- Manifest: https://cdn.jsdelivr.net/gh/faridbarahimi/AICP-VPN@config-feed/feed/telegram-all.json

The app also has jsDelivr and Raw.Githack mirrors for the raw GitHub source.

## Scheduling and publishing

The production scheduler is a systemd timer on the AICP VPS:

- Unit: `aicp-vpn-telegram-feed.timer`
- Cadence: every 20 minutes (`OnCalendar=*-*-* *:0/20:00`)
- Service: `aicp-vpn-telegram-feed.service`
- Publisher: `scripts/publish_telegram_config_feed.sh`

The service fast-forwards its isolated checkout from `main`, runs the collector, force-updates the orphan `config-feed` branch, and purges both jsDelivr edge paths. That branch contains only the current `feed/telegram-all.txt` and `feed/telegram-all.json` snapshot; old profiles and branch history are replaced, not appended.

GitHub Actions validates the collector on relevant pushes. `workflow_dispatch` is retained as a manual publishing fallback; it is not the production scheduler.

## Failure semantics and health

- If an enabled source fetch fails, or the resulting combined snapshot is empty, publishing fails and the previous snapshot remains. A successfully fetched source with no supported URI is omitted from that cycle only; its catalog entry is retained and rechecked on every run.
- Each successful complete run replaces the previous snapshot.
- The collector reads only public Telegram previews and requires no Telegram account, bot token, or session.
- The feed contains public proxy URIs; never add private subscriptions or user credentials.
- The collector does not probe whether proxies are alive. The Android app's real-ping and throughput qualification remain authoritative.
- GitHub-hosted raw content is public. Scheduled VPS execution is subject to network availability; the systemd timer catches up after downtime because `Persistent=true`.

## Sources

Enabled source channels are declared in `config/telegram-sources.json`. Currently enabled: `v2ray_free_conf`, `MARAMBASHI`, and `proxy_kafee` (Cafe Proxy). Cafe Proxy was temporarily removed after a preview check on 2026-11-15 returned no supported URI, but this was a false negative for a low-frequency publisher: on 2026-11-16 its public preview again contained fresh proxy profiles. It is now restored as a retained source. Low publication cadence alone must never disable or delete it; its latest visible profiles remain eligible when endpoint safety checks pass, and it is re-evaluated every run. Extracted profiles are not proof of live proxy connectivity. Proprietary NPV Tunnel `.npvt`/`.npvs` payloads are excluded until a compatible importer exists.

## Telegram Source Intelligence & Rotation

The independent `scripts/telegram_source_intelligence.py` subsystem performs public discovery via GitHub repository search and README extraction for public Telegram channel links, channel freshness checks, URI extraction, bounded TCP endpoint qualification, ranking, deduplication, and append-only per-source history. It is intentionally separate from the feed collector.

- Daily discovery/qualification: `aicp-vpn-source-intelligence.timer` / `.service` (03:17 UTC host-local systemd time, persistent timer).
- Per-source history and disabled discovery registry: `/var/lib/aicp-vpn-source-intelligence/` (local VPS state; never committed to Git).
- The every-20-minute publisher refreshes the qualification report and publishes `feed/telegram-source-intelligence.json` alongside the current text feed and manifest. The `config-feed` branch remains a single-root latest-snapshot branch; previous snapshot files/history are not accumulated there.
- Discovered channels are stored as disabled candidates. They are inspected and ranked, but never automatically enabled or allowed into the user feed. Activation remains a deliberate catalog change after review.
- Candidate sources are ranked by TCP-reachable endpoint count, then extracted count and freshness status. The rotation plan reorders enabled sources by this ranking and keeps low-frequency sources with safe endpoints eligible, and excludes only the current run’s missing/unsafe profiles without deleting their catalog entries; transient fetch errors block publication and preserve the previous snapshot. Sources whose latest post is older than their configured low-frequency threshold (30 days by default) are marked `low_frequency`, not dead. If they still expose safe endpoints, they remain eligible for the feed. Channels with no visible messages are marked inactive and channels with no supported URI are marked no-configs for that run, but neither status removes the source from the catalog; every configured source is checked again on subsequent runs.
- URI validation rejects malformed endpoints, localhost, private/reserved IP literals, and non-routable DNS answers during probing. DNS answers are vetted and the exact vetted socket address is used to reduce DNS-rebinding risk. Probe count and timeout are bounded.
- `extracted` = syntactically accepted unique URI count; `tested` = endpoints given a TCP test; `tcp_reachable` = endpoints accepting a TCP socket; `proxy_verified` = successful protocol-aware proxy traffic. The latter remains **0 / NOT VERIFIED** until a real client adapter performs a protocol handshake and authenticated request. TCP reachability alone is never called a working VPN.
- Source history is append-only local JSONL. The public GitHub snapshot includes only the latest report, not the history database.
- Discovery depends on public search-engine availability and is best-effort; failures are recorded in the report and do not silently activate sources.
