#!/usr/bin/env python3
"""Independent collector for supported public Telegram proxy URIs."""
from __future__ import annotations
import argparse, hashlib, html, json, re, sys, urllib.error, urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

URI_RE = re.compile(r"(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic)://[^\s<>\"']+")
TRAILING = ".,;!?)]}"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024

class MessageTextParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.active = False
        self.parts = []
        self.messages = []
    def handle_starttag(self, tag, attrs):
        classes = dict(attrs).get("class", "").split()
        if tag == "div" and "tgme_widget_message_text" in classes and not self.active:
            self.active, self.depth, self.parts = True, 1, []
        elif self.active:
            if tag == "br":
                self.parts.append("\n")
            elif tag not in {"img", "hr"}:
                self.depth += 1
    def handle_endtag(self, tag):
        if not self.active:
            return
        if tag == "div":
            self.depth -= 1
            if self.depth <= 0:
                value = "".join(self.parts).strip()
                if value:
                    self.messages.append(value)
                self.active, self.depth, self.parts = False, 0, []
        elif tag not in {"br", "img", "hr"}:
            self.depth = max(1, self.depth - 1)
    def handle_data(self, data):
        if self.active:
            self.parts.append(data)


def is_safe_config_uri(uri):
    """Reject malformed, local/private and non-routable proxy endpoints."""
    import ipaddress, base64
    try:
        scheme, raw = uri.split("://", 1)
        scheme = scheme.lower()
        if scheme == "vmess":
            token = raw.split("#", 1)[0].split("?", 1)[0]
            token += "=" * (-len(token) % 4)
            obj = json.loads(base64.urlsafe_b64decode(token.encode()).decode())
            host, port = str(obj.get("add", "")), int(obj.get("port", 0))
        elif scheme == "ss":
            parsed = urlsplit(uri)
            host, port = parsed.hostname or "", parsed.port or 0
            if not host:
                token = raw.split("#", 1)[0].split("@")[-1]
                if ":" not in token:
                    token += "=" * (-len(token) % 4)
                    token = base64.urlsafe_b64decode(token).decode()
                parsed = urlsplit("//" + token)
                host, port = parsed.hostname or "", parsed.port or 0
        else:
            parsed = urlsplit(uri)
            host, port = parsed.hostname or "", parsed.port or 0
            if scheme in {"vless", "trojan", "tuic", "hysteria2", "hy2"} and not parsed.username:
                return False
        if not host or not (1 <= int(port) <= 65535):
            return False
        try:
            if not ipaddress.ip_address(host.strip("[]")).is_global:
                return False
        except ValueError:
            name = host.rstrip(".").lower()
            if "." not in name or name in {"localhost", "metadata.google.internal"} or name.endswith((".local", ".internal", ".test", ".example", ".invalid")):
                return False
        return len(uri) <= 16384 and "\x00" not in uri
    except (ValueError, TypeError, KeyError, json.JSONDecodeError, UnicodeError, base64.binascii.Error):
        return False

def extract_uris(document):
    parser = MessageTextParser()
    parser.feed(document)
    result, seen = [], set()
    for message in parser.messages:
        for raw in URI_RE.findall(html.unescape(message)):
            uri = raw.rstrip(TRAILING)
            scheme = uri.split("://", 1)[0].lower()
            if len(uri) > 16384 or not is_safe_config_uri(uri):
                continue
            identity = uri.split("#", 1)[0]
            if not identity or identity in seen:
                continue
            if scheme != "vmess":
                try:
                    parsed = urlsplit(uri)
                    if not parsed.hostname:
                        continue
                    if scheme == "vless" and not parsed.username:
                        continue
                    if parsed.port is not None and not (1 <= parsed.port <= 65535):
                        continue
                except ValueError:
                    continue
            seen.add(identity)
            result.append(uri)
    return result

def fetch_channel(channel, timeout=20):
    if not re.fullmatch(r"[A-Za-z0-9_]{4,64}", channel):
        raise ValueError("invalid Telegram channel name")
    request = urllib.request.Request(
        f"https://t.me/s/{channel}",
        headers={"User-Agent":"AICP-VPN-TelegramConfigEngine/1.0","Accept":"text/html"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        final = urlsplit(response.geturl())
        if final.scheme != "https" or final.hostname != "t.me":
            raise ValueError("unexpected Telegram redirect host")
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError("Telegram preview exceeds size limit")
    return body.decode("utf-8", errors="replace")

def collect(source_config):
    config = json.loads(source_config.read_text(encoding="utf-8"))
    if config.get("schema_version") != 1 or not isinstance(config.get("sources"), list):
        raise ValueError("unsupported source catalog schema")
    merged, seen, results, errors = [], set(), [], []
    for source in config["sources"]:
        if not source.get("enabled", False):
            continue
        source_id, channel = str(source["id"]), str(source["channel"])
        try:
            document = fetch_channel(channel)
            uris = extract_uris(document)
            if not uris:
                if source.get("attachment_extensions"):
                    results.append({"id":source_id,"channel":channel,"status":"attachment_only","extracted":0})
                    continue
                raise ValueError("no supported proxy URIs in public preview")
            for uri in uris:
                identity = uri.split("#", 1)[0]
                if identity not in seen:
                    seen.add(identity)
                    merged.append(uri)
            results.append({"id":source_id,"channel":channel,"status":"ok","extracted":len(uris)})
        except (OSError, ValueError, urllib.error.URLError) as exc:
            errors.append(f"{source_id}: {type(exc).__name__}")
            results.append({"id":source_id,"channel":channel,"status":"error","extracted":0,"error":type(exc).__name__})
    if not results:
        raise ValueError("no enabled Telegram sources")
    if errors:
        raise RuntimeError("collection incomplete; preserving previous snapshot: " + ", ".join(errors))
    if not merged:
        raise RuntimeError("no supported profiles; preserving previous snapshot")
    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z")
    manifest = {
        "schema_version":1,"generated_at":timestamp,"source_count":len(results),
        "config_count":len(merged),
        "sha256":hashlib.sha256(("\n".join(merged)+"\n").encode()).hexdigest(),
        "sources":results,"health_status":"not_probed"
    }
    return merged, manifest

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, default=Path("config/telegram-sources.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--validated-uris", type=Path)
    parser.add_argument("--intelligence-report", type=Path)
    args = parser.parse_args()
    try:
        if args.validated_uris:
            configs = json.loads(args.validated_uris.read_text(encoding="utf-8"))
            if not isinstance(configs, list) or not configs or any(not isinstance(x, str) or not is_safe_config_uri(x) for x in configs):
                raise ValueError("validated URI input must be a non-empty string array")
            unique, seen = [], set()
            for uri in configs:
                identity = uri.split("#", 1)[0]
                if identity not in seen:
                    seen.add(identity); unique.append(uri)
            configs = unique
            timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
            manifest = {"schema_version": 1, "generated_at": timestamp, "config_count": len(configs),
                        "sha256": hashlib.sha256(("\n".join(configs)+"\n").encode()).hexdigest(),
                        "health_status": "tcp_reachability_probed"}
            if args.intelligence_report:
                intelligence = json.loads(args.intelligence_report.read_text(encoding="utf-8"))
                manifest.update({"source_count": intelligence.get("source_count", 0),
                                 "sources": intelligence.get("ranked_sources", []),
                                 "extracted_count": intelligence.get("extracted", 0),
                                 "tested_count": intelligence.get("tested", 0),
                                 "tcp_reachable_count": intelligence.get("tcp_reachable", 0),
                                 "proxy_verified_count": intelligence.get("proxy_verified", 0),
                                 "unique_safe_config_count": intelligence.get("feed_config_count", len(configs)),
                                 "feed_tested_count": intelligence.get("feed_tested", 0),
                                 "feed_tcp_reachable_count": intelligence.get("feed_tcp_reachable", 0),
                                 "feed_extracted_count": intelligence.get("feed_extracted", 0)})
                external = intelligence.get("external_sources") or {}
                manifest["external_sources"] = external
                manifest["external_protocol_verified_count"] = external.get("included", external.get("protocol_verified_candidates", 0))
                if manifest["external_protocol_verified_count"]:
                    manifest["health_status"] = "mixed_telegram_tcp_and_external_sing_box_verified"
        else:
            configs, manifest = collect(args.sources)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    header = ["# AICP VPN rolling Telegram feed",f"# generated_at={manifest['generated_at']}",
              f"# config_count={manifest['config_count']}",f"# sha256={manifest['sha256']}"]
    args.output.write_text("\n".join(header+configs)+"\n",encoding="utf-8")
    args.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest,ensure_ascii=False))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
