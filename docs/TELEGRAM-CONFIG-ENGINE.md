# AICP VPN Telegram Config Engine

The independent collector fetches configured public Telegram previews every 20 minutes, extracts only supported VLESS, VMess, Trojan, Shadowsocks, Hysteria2/Hy2 and TUIC URIs, and deduplicates by URI without its fragment.

Published files:
- Text feed: https://raw.githubusercontent.com/faridbarahimi/AICP-VPN/config-feed/feed/telegram-all.txt
- Manifest: https://raw.githubusercontent.com/faridbarahimi/AICP-VPN/config-feed/feed/telegram-all.json

Each successful run replaces the prior snapshot. The feed branch is force-updated from an orphan commit so only the latest snapshot remains reachable from that branch. If any enabled source fails or no supported profiles are found, publication fails and the previous snapshot remains. This is deliberate fail-safe behavior.

The collector reads only public Telegram previews and requires no Telegram account, bot token, or session. The feed contains public proxy URIs; never add private subscriptions or user credentials. The collector does not probe whether proxies are alive; Android real-ping and throughput qualification remain authoritative.

Sources are configured in config/telegram-sources.json. Proprietary NPV Tunnel .npvt/.npvs files are excluded until a compatible importer exists. GitHub scheduled workflows are best-effort and may start late under platform load.
