#!/usr/bin/env python3
"""Collect and qualify public external VPN datasets without auto-publishing them.

Public subscription candidates, Cloudflare/Radar ranges, clean-IP pools, WARP
endpoints and public proxy lists are separate data classes. Only globally
routable endpoints are probed. TCP reachability is never called proxy verification.
Raw candidate URIs are written only to the private state directory (0600).
"""
from __future__ import annotations
import base64, hashlib, ipaddress, json, re, socket, sys, time, urllib.parse, urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from datetime import datetime, timezone
from scripts.telegram_config_engine import is_safe_config_uri
from scripts.telegram_source_intelligence import endpoint, probe_tcp

USER_AGENT = "AICP-VPN-External-Source-Intelligence/1.0"
MAX_BYTES = 5 * 1024 * 1024
MAX_CONFIGS_PER_SOURCE = 2500
MAX_PROBES = 120
URI_RE = re.compile(r"(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic)://[^\s<>\"']+")
TRAILING = ".,;!?)]}"

# Mirrors the independently published Nova free-list inputs. These are candidate
# providers, not trusted publishers. Per-source failures are recorded separately.
SUBSCRIPTION_SOURCES = [
    "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/mix",
    "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vless",
    "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/trojan",
    "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt",
    "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
    "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
    "https://raw.githubusercontent.com/miladtahanian/Config-Collector/main/mixed_iran.txt",
    "https://raw.githubusercontent.com/9Knight9n/v2ray-aggregator/main/merged_24h.txt",
    "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/continents/Asia.txt",
    "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/continents/Europe.txt",
    "https://raw.githubusercontent.com/TheCrowCreature/v2rayExtractor/main/scripts/sub-checker/normal.txt",
    "https://raw.githubusercontent.com/Argh73/VpnConfigCollector/main/All_Configs_Sorted.txt",
    "https://raw.githubusercontent.com/myominn062-svg/mk-studio-vpn-service/main/all_configs.txt",
    "https://raw.githubusercontent.com/3yed-61/configs-collector/main/classified_output/vless.txt",
    "https://raw.githubusercontent.com/thealiiakbarii-ai/VCC/main/configs/all.txt",
    "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/splitted/mixed",
]
DATASETS = {
    "cloudflare_ipv4": "https://www.cloudflare.com/ips-v4/",
    "cm_cidr": "https://raw.githubusercontent.com/cmliu/cmliu/master/CF-CIDR.txt",
    "as13335": "https://raw.githubusercontent.com/ipverse/asn-ip/master/as/13335/ipv4-aggregated.txt",
    "as209242": "https://raw.githubusercontent.com/ipverse/asn-ip/master/as/209242/ipv4-aggregated.txt",
    "as24429": "https://raw.githubusercontent.com/ipverse/asn-ip/master/as/24429/ipv4-aggregated.txt",
    "as199524": "https://raw.githubusercontent.com/ipverse/asn-ip/master/as/199524/ipv4-aggregated.txt",
    "reverse_proxy_ips": "https://raw.githubusercontent.com/cmliu/ACL4SSR/main/baipiao.txt",
    "iran_ipv4_ranges": "https://raw.githubusercontent.com/ipverse/rir-ip/master/country/ir/ipv4-aggregated.txt",
    "warp_endpoints": "https://raw.githubusercontent.com/IRNova/Tools/main/warp-endpoints.txt",
    "clean_ip_all": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/all.txt",
    "clean_ip_mci": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/mci.txt",
    "clean_ip_mtn": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/mtn.txt",
    "clean_ip_rightel": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/rightel.txt",
    "clean_ip_shatel": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/shatel.txt",
    "clean_ip_tci": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/tci.txt",
    "clean_ip_itc": "https://raw.githubusercontent.com/IRNova/Tools/main/clean-ip/itc.txt",
    "proxifly_socks5": "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.json",
    "proxifly_http": "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.json",
}


def utcnow():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fetch_text(url: str, timeout: int = 8) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in {"raw.githubusercontent.com", "www.cloudflare.com"}:
        raise ValueError("unapproved_source_host")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/plain,*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        final = urllib.parse.urlsplit(response.geturl())
        if final.scheme != "https" or final.hostname not in {"raw.githubusercontent.com", "www.cloudflare.com"}:
            raise ValueError("unsafe_redirect")
        body = response.read(MAX_BYTES + 1)
    if len(body) > MAX_BYTES:
        raise ValueError("response_too_large")
    return body.decode("utf-8", "replace")


def decode_subscription(body: str) -> str:
    text = body.strip()
    if "://" in text:
        return text
    compact = re.sub(r"\s+", "", text)
    if len(compact) < 24 or not re.fullmatch(r"[A-Za-z0-9_+/=-]+", compact):
        return text
    try:
        decoded = base64.b64decode(compact + "=" * (-len(compact) % 4), validate=True).decode("utf-8")
        return decoded if "://" in decoded else text
    except (ValueError, UnicodeError):
        return text


def extract_config_candidates(body: str) -> list[str]:
    found, seen = [], set()
    for raw in URI_RE.findall(decode_subscription(body)):
        uri = raw.rstrip(TRAILING)
        identity = uri.split("#", 1)[0]
        if identity and identity not in seen and is_safe_config_uri(uri):
            seen.add(identity)
            found.append(uri)
            if len(found) >= MAX_CONFIGS_PER_SOURCE:
                break
    return found


def parse_proxy_records(body: str) -> list[tuple[str, int]]:
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return parse_host_ports(body)
    if isinstance(payload, dict):
        payload = payload.get("data", payload.get("proxies", []))
    values, seen = [], set()
    if not isinstance(payload, list):
        return values
    for row in payload:
        if not isinstance(row, dict):
            continue
        host = str(row.get("ip", row.get("host", row.get("ip_address", ""))))
        try:
            port = int(row.get("port", 0)); ip = ipaddress.ip_address(host)
            if ip.is_global and 1 <= port <= 65535 and (str(ip), port) not in seen:
                seen.add((str(ip), port)); values.append((str(ip), port))
        except (ValueError, TypeError):
            continue
    return values


def parse_ip_or_cidr_lines(body: str) -> list[str]:
    values, seen = [], set()
    for line in body.splitlines():
        value = line.strip().split("#", 1)[0].strip()
        if not value:
            continue
        try:
            net = ipaddress.ip_network(value, strict=False)
            if net.version != 4 or not net.network_address.is_global:
                continue
            canonical = str(net)
        except ValueError:
            continue
        if canonical not in seen:
            seen.add(canonical); values.append(canonical)
    return values


def parse_host_ports(body: str) -> list[tuple[str, int]]:
    values, seen = [], set()
    for line in body.splitlines():
        value = line.strip().split("#", 1)[0].strip()
        if not value:
            continue
        # Accept bare IPv4:port or host:port; never accept URL schemes/userinfo.
        if "://" in value or "@" in value:
            continue
        try:
            host, port_text = value.rsplit(":", 1)
            port = int(port_text)
            ip = ipaddress.ip_address(host.strip("[]"))
            if not ip.is_global or not 1 <= port <= 65535:
                continue
            item = (str(ip), port)
            if item not in seen:
                seen.add(item); values.append(item)
        except (ValueError, TypeError):
            continue
    return values


def run(state_dir: Path, verify_protocol: bool = False) -> dict:
    state_dir.mkdir(parents=True, exist_ok=True)
    errors, source_results, config_map = [], [], {}
    for url in SUBSCRIPTION_SOURCES:
        source_id = "/".join(urllib.parse.urlsplit(url).path.strip("/").split("/")[:2])
        try:
            candidates = extract_config_candidates(fetch_text(url))
            for uri in candidates:
                config_map.setdefault(uri.split("#", 1)[0], uri)
            source_results.append({"source": source_id, "status": "ok", "candidates": len(candidates)})
        except Exception as exc:
            errors.append({"source": source_id, "error": type(exc).__name__})
            source_results.append({"source": source_id, "status": "error", "candidates": 0})

    dataset_results, datasets = {}, {}
    for name, url in DATASETS.items():
        try:
            body = fetch_text(url)
            if name == "warp_endpoints":
                values = parse_host_ports(body)
            elif name.startswith("proxifly_"):
                values = parse_proxy_records(body)
            else:
                values = parse_ip_or_cidr_lines(body)
            datasets[name] = values
            dataset_results[name] = {"status": "ok", "entries": len(values), "sha256": hashlib.sha256(body.encode()).hexdigest()}
        except Exception as exc:
            dataset_results[name] = {"status": "error", "entries": 0, "error": type(exc).__name__}

    # Bounded TCP-only screening; these candidates remain private and are NOT
    # eligible for the public feed until a protocol-aware client test succeeds.
    probe_results, reachable = [], 0
    for uri in list(config_map.values())[:MAX_PROBES]:
        outcome = probe_tcp(uri, timeout=1.5)
        if outcome["status"] == "tcp_reachable": reachable += 1
        probe_results.append({"identity": hashlib.sha256(uri.split("#", 1)[0].encode()).hexdigest(), **outcome})

    candidates_path = state_dir / "external-config-candidates.json"
    candidates_path.write_text(json.dumps(list(config_map.values()), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    candidates_path.chmod(0o600)
    datasets_path = state_dir / "external-network-datasets.json"
    datasets_path.write_text(json.dumps(datasets, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    datasets_path.chmod(0o600)
    verification_report = None
    if verify_protocol:
        core = Path("/opt/aicp-vpn-tools/sing-box-1.14.4/sing-box")
        if core.is_file():
            try:
                from scripts.verify_external_configs import run as verify_configs
                verification_report = verify_configs(candidates_path, state_dir / "external-config-verified.json",
                    state_dir / "external-verification-report.json", core, limit=200)
            except Exception as exc:
                verification_report = {"status": "error", "error": type(exc).__name__, "protocol_verified": 0}
        else:
            verification_report = {"status": "blocked", "reason": "pinned_sing_box_binary_missing", "protocol_verified": 0}
    report = {
        "schema_version": 1, "generated_at": utcnow(),
        "subscription_source_count": len(SUBSCRIPTION_SOURCES),
        "subscription_sources_ok": sum(x["status"] == "ok" for x in source_results),
        "subscription_sources_failed": sum(x["status"] == "error" for x in source_results),
        "unique_safe_config_candidates": len(config_map),
        "tcp_probed_candidates": len(probe_results), "tcp_reachable_candidates": reachable,
        "protocol_verified_candidates": (verification_report or {}).get("protocol_verified", 0),
        "verification": verification_report,
        "publication_policy": "external candidates are quarantined; no candidate is auto-published without protocol-aware authenticated traffic verification and review",
        "subscription_sources": source_results, "datasets": dataset_results,
        "errors": errors,
    }
    report_path = state_dir / "latest-external-source-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path.chmod(0o600)
    return report

if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(); p.add_argument("--state-dir", type=Path, default=Path("/var/lib/aicp-vpn-source-intelligence")); p.add_argument("--verify-protocol", action="store_true")
    a=p.parse_args(); print(json.dumps(run(a.state_dir, a.verify_protocol), ensure_ascii=False))
