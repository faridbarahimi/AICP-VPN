# External VPN Source Intelligence

## Purpose and trust boundary

This subsystem imports public candidate data inspired by Nova's independently
published source collector. It is deliberately separate from the Telegram
catalog. Third-party feeds are untrusted input: a syntactically valid URI or a
successful TCP connect is not evidence that a proxy can carry user traffic.

Raw external subscription candidates and network datasets are stored only under
`/var/lib/aicp-vpn-source-intelligence/` with mode `0600`. They are never
committed to Git. Only fresh VLESS/Trojan profiles that pass a real sing-box
outbound HTTP request may be merged into the rolling public feed. The
verification snapshot expires after 30 hours. Unsupported protocols remain
quarantined rather than being treated as dead or working.

## Candidate providers

The subscription collector tracks 16 public raw-text sources from:

- iboxz/free-v2ray-collector (mix, VLESS, Trojan)
- barry-far/V2ray-Config
- Epodonios/v2ray-configs
- mahdibland/V2RayAggregator
- miladtahanian/Config-Collector
- 9Knight9n/v2ray-aggregator
- rtwo2/FastNodes (Asia and Europe)
- TheCrowCreature/v2rayExtractor
- Argh73/VpnConfigCollector
- myominn062-svg/mk-studio-vpn-service
- 3yed-61/configs-collector
- thealiiakbarii-ai/VCC
- Surfboardv2ray/TGParse

Each provider is fetched over HTTPS from allowlisted raw GitHub hosts, bounded
by a 5 MiB response cap, parsed as plain or base64 subscription text, URI
validated, deduplicated and endpoint-checked. Individual provider failures are
recorded; they do not invalidate the existing Telegram snapshot.

## Network datasets

Separate datasets are collected for Cloudflare IPv4 ranges, CM CIDRs, AS13335,
AS209242, AS24429 and AS199524, reverse-proxy IP candidates, Iranian IPv4
ranges, Nova's WARP endpoint pool, Nova's per-operator clean-IP files, and
Proxifly SOCKS5/HTTP lists. CIDR, IP:port and JSON proxy records are parsed into
typed datasets. They are not converted into VLESS/Trojan profiles and are not
published as user proxy configurations.

Clean-IP files containing placeholders or no valid public IPs remain empty;
missing operator files are reported as unavailable. WARP endpoints and public
SOCKS/HTTP proxies are candidate data only and are not considered trustworthy
without their own protocol-specific verification.

## Protocol verification

Pinned binary: sing-box 1.14.4, ARM64. Its release archive SHA-256 is checked
against the digest published by the official SagerNet GitHub release API before
installation. The verifier supports VLESS/Trojan over TLS with TCP, WebSocket,
gRPC and HTTPUpgrade transports. It starts isolated local Clash API instances
and requests `HTTP://www.gstatic.com/generate_204` through each outbound.
Only a successful delay response is considered protocol-verified. Other
protocols (VMess, Shadowsocks, Hysteria2, TUIC, Reality, etc.) remain
quarantined until adapters are implemented and tested.

The first live sample (2026-11-16): 9,956 unique safe URI candidates from 11 of
16 reachable source URLs; 120 TCP checks in the general candidate sample had
84 reachable. A protocol-specific run selected 200 supported VLESS/Trojan
candidates, 179 passed TCP, all 179 were exercised through sing-box, and 64
returned a successful HTTP 204 probe. This is a sample, not a claim that all
9,956 candidates are usable.

## Scheduling and publication

The daily `aicp-vpn-source-intelligence.service` collects Telegram discovery,
external candidate feeds and typed network datasets, then performs the bounded
sing-box verification. The 20-minute publisher merges only a fresh (<=30 hour)
verified snapshot into the current Telegram profiles before building the
single-root `config-feed` branch snapshot. It does not merge unverified feeds,
public SOCKS/HTTP relays, WARP endpoints or raw IP lists.

The publisher remains fail-closed for Telegram source errors and empty output.
Cafe Proxy remains enabled and retained independently of publication cadence.

## Known limitations

- The external collector currently samples at most 200 protocol-compatible
  candidates per daily verification cycle; this is a bounded initial tranche.
- Current sing-box adapter verifies only VLESS/Trojan TLS and supported
  transports. Other schemes need dedicated adapters.
- HTTP 204 verification is a point-in-time connectivity check, not a guarantee
  of sustained speed, privacy, anonymity, censorship resistance or provider
  trustworthiness. Android client ping/throughput checks remain authoritative.
- Nova's Cloudflare clean-IP files currently contain placeholders/empty
  datasets; the collector records this rather than inventing IPs.
- Public SOCKS5/HTTP lists may contain transparent or hostile relays; they are
  stored only as candidate network data and never placed in the user feed.
