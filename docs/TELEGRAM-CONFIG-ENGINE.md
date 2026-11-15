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

The service fast-forwards its isolated checkout from `main`, runs the collector, and force-updates the orphan `config-feed` branch. That branch contains only the current `feed/telegram-all.txt` and `feed/telegram-all.json` snapshot; old profiles and branch history are replaced, not appended.

GitHub Actions validates the collector on relevant pushes. `workflow_dispatch` is retained as a manual publishing fallback; it is not the production scheduler.

## Failure semantics and health

- If any enabled source fails, has no supported URIs, or the resulting snapshot is empty, publishing fails and the previous snapshot remains.
- Each successful complete run replaces the previous snapshot.
- The collector reads only public Telegram previews and requires no Telegram account, bot token, or session.
- The feed contains public proxy URIs; never add private subscriptions or user credentials.
- The collector does not probe whether proxies are alive. The Android app's real-ping and throughput qualification remain authoritative.
- GitHub-hosted raw content is public. Scheduled VPS execution is subject to network availability; the systemd timer catches up after downtime because `Persistent=true`.

## Sources

Enabled source channels are declared in `config/telegram-sources.json`. Currently enabled: `proxy_kafee` and `MARAMBASHI`. Proprietary NPV Tunnel `.npvt`/`.npvs` payloads are excluded until a compatible importer exists.
