#!/usr/bin/env python3
"""Discover, qualify, rank and audit public Telegram proxy sources.

Network probes are deliberately bounded and only target public, globally-routable
endpoint addresses. TCP reachability is NOT represented as a successful proxy session.
"""
from __future__ import annotations
import argparse, base64, hashlib, html, json, re, socket, sys, time, urllib.parse, urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.telegram_config_engine import extract_uris, fetch_channel

TG_LINK = re.compile(r"(?:https?://)?(?:www\.)?t\.me/([A-Za-z0-9_]{4,64})(?:\?[^\s\"'<>]*)?", re.I)
DATE_RE = re.compile(r'datetime=[\"\']([^\"\']+)[\"\']', re.I)
MAX_DISCOVERED = 30
MAX_PROBES_PER_SOURCE = 80


def utcnow():
    return datetime.now(timezone.utc)


def public_host(host: str) -> bool:
    try:
        import ipaddress
        ip = ipaddress.ip_address(host.strip("[]"))
        return ip.is_global
    except ValueError:
        name = host.rstrip(".").lower()
        return bool(name and "." in name and not (name == "localhost" or name.endswith((".local", ".internal", ".test", ".invalid", ".example"))))


def endpoint(uri: str):
    scheme, raw = uri.split("://", 1)
    if scheme.lower() == "vmess":
        token = raw.split("#", 1)[0].split("?", 1)[0]
        token += "=" * (-len(token) % 4)
        obj = json.loads(base64.urlsafe_b64decode(token.encode()).decode())
        host, port = str(obj.get("add", "")), int(obj.get("port", 0))
    elif scheme.lower() == "ss":
        parsed = urllib.parse.urlsplit(uri)
        host, port = parsed.hostname or "", parsed.port or 0
        if not host:
            token = raw.split("#", 1)[0]
            token = token.split("@")[-1]
            if ":" not in token:
                token += "=" * (-len(token) % 4)
                try: token = base64.urlsafe_b64decode(token).decode()
                except Exception: pass
            parsed = urllib.parse.urlsplit("//" + token)
            host, port = parsed.hostname or "", parsed.port or 0
    else:
        parsed = urllib.parse.urlsplit(uri)
        host, port = parsed.hostname or "", parsed.port or 0
    if not host or not (1 <= int(port) <= 65535) or not public_host(host):
        raise ValueError("unsafe_or_invalid_endpoint")
    return host, int(port)


def probe_tcp(uri: str, timeout: float = 2.0) -> dict:
    try:
        import ipaddress
        host, port = endpoint(uri)
        try:
            addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError:
            return {"status": "dns_unresolved", "latency_ms": None}
        if not addresses:
            return {"status": "dns_unresolved", "latency_ms": None}
        # Reject the entire hostname if any DNS answer is non-public. Connect to
        # the exact vetted sockaddr to avoid a second DNS lookup / rebinding gap.
        vetted = []
        for family, socktype, proto, _canon, sockaddr in addresses:
            if not ipaddress.ip_address(sockaddr[0]).is_global:
                return {"status": "rejected_unsafe", "latency_ms": None}
            vetted.append((family, socktype, proto, sockaddr))
        started = time.monotonic()
        for family, socktype, proto, sockaddr in vetted:
            sock = socket.socket(family, socktype, proto)
            sock.settimeout(timeout)
            try:
                sock.connect(sockaddr)
                return {"status": "tcp_reachable", "latency_ms": round((time.monotonic()-started)*1000, 2)}
            except OSError:
                pass
            finally:
                sock.close()
        return {"status": "tcp_unreachable", "latency_ms": None}
    except (ValueError, TypeError, KeyError, json.JSONDecodeError, UnicodeError):
        return {"status": "rejected_unsafe", "latency_ms": None}


def extract_channel_candidates(document: str):
    return sorted({m.group(1) for m in TG_LINK.finditer(html.unescape(document))}, key=str.lower)


def discover_channels(queries=("v2ray telegram config", "vless telegram channels"), timeout=12):
    """Discover candidate public channels referenced by recently updated public repos."""
    found, evidence, seen_repos = {}, [], set()
    headers = {"User-Agent": "AICP-Source-Intelligence/1.0", "Accept": "application/vnd.github+json"}
    for query in queries:
        url = "https://api.github.com/search/repositories?q=" + urllib.parse.quote(query) + "&sort=updated&per_page=10"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as response:
                payload = json.loads(response.read(1_000_000).decode("utf-8"))
            scanned = 0
            for repo in payload.get("items", []):
                name = repo.get("full_name", "")
                if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", name) or name in seen_repos:
                    continue
                seen_repos.add(name)
                branch = repo.get("default_branch", "main")
                readme_url = f"https://raw.githubusercontent.com/{name}/{urllib.parse.quote(branch, safe='')}/README.md"
                try:
                    readme_req = urllib.request.Request(readme_url, headers={"User-Agent": "AICP-Source-Intelligence/1.0"})
                    with urllib.request.urlopen(readme_req, timeout=8) as readme_response:
                        document = readme_response.read(500_001)
                    if len(document) > 500_000:
                        continue
                    for slug in extract_channel_candidates(document.decode("utf-8", "replace")):
                        found.setdefault(slug.lower(), slug)
                    scanned += 1
                except Exception:
                    continue
            evidence.append({"provider": "github_repository_search", "query": query, "status": "ok", "repositories_scanned": scanned})
        except Exception as exc:
            evidence.append({"provider": "github_repository_search", "query": query, "status": "error", "error": type(exc).__name__})
    return list(found.values())[:MAX_DISCOVERED], evidence


def inspect_source(source, now=None, probe_limit=MAX_PROBES_PER_SOURCE):
    now = now or utcnow()
    sid, channel = source["id"], source["channel"]
    result = {"id": sid, "channel": channel, "enabled": bool(source.get("enabled", False)),
              "discovered": bool(source.get("discovered", False)), "status": "unknown",
              "message_count": 0, "extracted": 0, "safe_configs": 0, "tested": 0, "tcp_reachable": 0, "proxy_verified": 0,
              "last_post_at": None, "last_seen_at": now.isoformat(timespec="seconds"), "errors": []}
    try:
        page = fetch_channel(channel)
        dates = DATE_RE.findall(page)
        result["message_count"] = len(re.findall(r"tgme_widget_message_wrap", page))
        parsed_dates = []
        for value in dates:
            try: parsed_dates.append(datetime.fromisoformat(value.replace("Z", "+00:00")))
            except ValueError: pass
        if parsed_dates:
            last_post = max(parsed_dates)
            result["last_post_at"] = last_post.isoformat()
            result["age_days"] = round((now-last_post).total_seconds()/86400, 2)
            low_frequency_after = int(source.get("low_frequency_after_days", 30))
            if now-last_post > timedelta(days=low_frequency_after): result["status"] = "low_frequency"
        uris = extract_uris(page)
        result["extracted"] = len(uris)
        if not uris:
            result["status"] = "inactive" if result["message_count"] == 0 else "no_configs"
            return result, []
        probes = []
        safe_uris = []
        for uri in uris[:probe_limit]:
            outcome = probe_tcp(uri)
            probes.append(outcome)
            if outcome["status"] in {"tcp_reachable", "tcp_unreachable"}: safe_uris.append(uri)
            result["tested"] += 1
            if outcome["status"] == "tcp_reachable": result["tcp_reachable"] += 1
        result["safe_configs"] = len(safe_uris)
        result["proxy_verified"] = 0  # requires a protocol-aware client/auth handshake, not a TCP connect
        if result["status"] == "low_frequency" and safe_uris:
            # Low publication cadence is not evidence of source failure. Keep
            # previously published profiles eligible and re-check on every run.
            pass
        else:
            result["status"] = "active" if result["tcp_reachable"] else ("no_reachable_endpoints" if safe_uris else "no_safe_endpoints")
        result["median_tcp_latency_ms"] = sorted(x["latency_ms"] for x in probes if x["latency_ms"] is not None)[len([x for x in probes if x["latency_ms"] is not None])//2] if result["tcp_reachable"] else None
        return result, safe_uris
    except Exception as exc:
        result["status"] = "error"
        result["errors"].append(type(exc).__name__)
        return result, []


def run(catalog_path: Path, history_path: Path, report_path: Path, discover=True, registry_path: Path | None = None, effective_catalog_path: Path | None = None, validated_uris_path: Path | None = None, include_external=False, verify_external=False):
    cfg = json.loads(catalog_path.read_text(encoding="utf-8"))
    if cfg.get("schema_version") != 1 or not isinstance(cfg.get("sources"), list):
        raise ValueError("invalid source catalog")
    known = {s["channel"].lower() for s in cfg["sources"]}
    registry_path = registry_path or history_path.parent / "discovered-sources.json"
    registry_path.parent.mkdir(parents=True, exist_ok=True)
    try: registry = json.loads(registry_path.read_text(encoding="utf-8"))
    except FileNotFoundError: registry = {"schema_version": 1, "sources": []}
    if registry.get("schema_version") != 1 or not isinstance(registry.get("sources"), list):
        raise ValueError("invalid discovered-source registry")
    discovered, discovery_evidence = discover_channels() if discover else ([], [])
    sources = list(cfg["sources"])
    candidates = {x["channel"].lower(): x for x in registry["sources"]}
    for channel in discovered:
        if channel.lower() not in known and channel.lower() not in candidates:
            candidates[channel.lower()] = {"id": "discovered-" + re.sub(r"[^a-z0-9]+", "-", channel.lower()).strip("-")[:48],
                                           "channel": channel, "enabled": False, "discovered": True}
    registry["sources"] = sorted(candidates.values(), key=lambda x: x["channel"].lower())
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for candidate in registry["sources"]:
        if candidate["channel"].lower() not in known:
            sources.append(candidate)
    records, config_map, source_uris = [], {}, {}
    for source in sources:
        rec, uris = inspect_source(source)
        source_uris[rec["id"]] = uris
        for uri in uris:
            # Identity ignores the display fragment; retain only first occurrence.
            identity = hashlib.sha256(uri.split("#", 1)[0].encode()).hexdigest()
            config_map.setdefault(identity, uri)
        records.append(rec)
    ranked = sorted(records, key=lambda x: (x["tcp_reachable"], x["extracted"], x["status"] == "active"), reverse=True)
    for rank, rec in enumerate(ranked, 1): rec["rank"] = rank
    history_path.parent.mkdir(parents=True, exist_ok=True)
    with history_path.open("a", encoding="utf-8") as out:
        for rec in records:
            out.write(json.dumps({"recorded_at": utcnow().isoformat(timespec="seconds"), **rec}, ensure_ascii=False) + "\n")
    enabled_ids = {s["id"] for s in cfg["sources"] if s.get("enabled")}
    selected = [r for r in ranked if r["id"] in enabled_ids and r["status"] in {"active", "low_frequency", "no_reachable_endpoints"} and r["safe_configs"] > 0]
    enabled_errors = [r for r in ranked if r["id"] in enabled_ids and r["status"] == "error"]
    excluded = [r for r in ranked if r["id"] in enabled_ids and r["status"] in {"stale", "no_configs", "no_safe_endpoints", "inactive"}]
    rotation_blocked = bool(enabled_errors) or not selected
    feed_config_map = {}
    if not rotation_blocked:
        # Build the public feed strictly from ranked, already-enabled sources.
        # Discovered candidates are measured but never leak into the feed.
        for rec in selected:
            for uri in source_uris.get(rec["id"], []):
                identity = hashlib.sha256(uri.split("#", 1)[0].encode()).hexdigest()
                feed_config_map.setdefault(identity, uri)
    if validated_uris_path:
        validated_uris_path.parent.mkdir(parents=True, exist_ok=True)
        validated_uris_path.write_text(json.dumps(list(feed_config_map.values()), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if effective_catalog_path:
        effective_catalog_path.parent.mkdir(parents=True, exist_ok=True)
        rank_by_id = {rec["id"]: rec["rank"] for rec in ranked}
        # Preserve every explicitly configured source in the effective catalog;
        # this is a ranking view, not a deletion/disable list.
        effective_sources = sorted(cfg["sources"], key=lambda x: rank_by_id.get(x["id"], 10**9))
        effective_catalog_path.write_text(json.dumps({"schema_version": 1, "sources": effective_sources}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    external_report = None
    if include_external:
        try:
            from scripts.external_source_intelligence import run as run_external_sources
            external_report = run_external_sources(history_path.parent, verify_protocol=verify_external)
        except Exception as exc:
            external_report = {"status": "error", "error": type(exc).__name__, "protocol_verified_candidates": 0}
    report = {"schema_version": 1, "generated_at": utcnow().isoformat(timespec="seconds"),
              "discovery": {"candidate_count": len(discovered), "candidates": discovered, "queries": discovery_evidence},
              "source_count": len(records), "active_sources": sum(r["status"] == "active" for r in records),
              "stale_sources": sum(r["status"] == "stale" for r in records),
              "low_frequency_sources": sum(r["status"] == "low_frequency" for r in records),
              "no_config_sources": sum(r["status"] == "no_configs" for r in records),
              "inactive_sources": sum(r["status"] == "inactive" for r in records),
              "disabled_discovered_candidates": sum(r["discovered"] for r in records),
              "extracted": sum(r["extracted"] for r in records),
              "safe_configs": len(config_map),
              "tested": sum(r["tested"] for r in records),
              "tcp_reachable": sum(r["tcp_reachable"] for r in records),
              "proxy_verified": sum(r["proxy_verified"] for r in records),
              "unique_configs": len(config_map), "feed_config_count": len(feed_config_map),
              "feed_extracted": sum(r["extracted"] for r in selected),
              "feed_tested": sum(r["tested"] for r in selected),
              "feed_tcp_reachable": sum(r["tcp_reachable"] for r in selected),
              "external_sources": external_report,
              "ranked_sources": ranked,
              "rotation": {"mode": "ranked_existing_sources", "blocked": rotation_blocked,
                           "retention_policy": "configured sources are never auto-deleted; low-frequency sources with safe endpoints remain eligible; sources without current profiles are rechecked on every run",
                           "selected_sources": [{"id": r["id"], "channel": r["channel"], "rank": r["rank"], "extracted": r["extracted"], "safe_configs": r["safe_configs"], "tcp_reachable": r["tcp_reachable"]} for r in selected],
                           "excluded_sources": [{"id": r["id"], "channel": r["channel"], "reason": r["status"]} for r in excluded],
                           "error_sources": [{"id": r["id"], "channel": r["channel"]} for r in enabled_errors],
                           "new_candidates_auto_enabled": False},
              "selection_policy": "existing enabled sources only; discovered sources require explicit review before activation; low-frequency enabled sources with safe endpoints remain eligible",
              "connectivity_semantics": "Telegram tcp_reachable is a TCP socket check only and Telegram proxy_verified remains zero; external profiles are reported separately and only supported VLESS/Trojan TLS candidates passing sing-box HTTP 204 are protocol-verified"}
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--catalog", type=Path, default=Path("config/telegram-sources.json"))
    p.add_argument("--history", type=Path, default=Path("state/source-history.jsonl"))
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--registry", type=Path, default=Path("state/discovered-sources.json"))
    p.add_argument("--effective-catalog", type=Path)
    p.add_argument("--validated-uris-output", type=Path)
    p.add_argument("--no-discovery", action="store_true")
    p.add_argument("--include-external", action="store_true", help="collect public external datasets into private quarantine")
    p.add_argument("--verify-external", action="store_true", help="verify selected external candidates with pinned sing-box")
    a = p.parse_args()
    try:
        result = run(a.catalog, a.history, a.report, not a.no_discovery, a.registry, a.effective_catalog, a.validated_uris_output, a.include_external, a.verify_external)
        print(json.dumps({k:v for k,v in result.items() if k != "ranked_sources"}, ensure_ascii=False))
        return 0
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__": raise SystemExit(main())
