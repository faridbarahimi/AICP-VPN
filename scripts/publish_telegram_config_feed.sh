#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d /tmp/aicp-vpn-telegram-feed.XXXXXX)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# Refresh the isolated collector checkout; fail closed if source code cannot be updated.
git -C "$REPO_ROOT" pull --ff-only origin main

mkdir -p "$TMP_DIR/feed"
STATE_DIR="/var/lib/aicp-vpn-source-intelligence"
mkdir -p "$STATE_DIR"
python3 "$REPO_ROOT/scripts/telegram_source_intelligence.py" \
  --catalog "$REPO_ROOT/config/telegram-sources.json" \
  --history "$STATE_DIR/source-history.jsonl" \
  --registry "$STATE_DIR/discovered-sources.json" \
  --report "$TMP_DIR/feed/telegram-source-intelligence.json" \
  --effective-catalog "$TMP_DIR/rotated-sources.json" \
  --validated-uris-output "$TMP_DIR/validated-uris.json" \
  --no-discovery
chmod 0600 "$STATE_DIR/latest-report.json"
python3 - "$TMP_DIR/feed/telegram-source-intelligence.json" <<'PYROTATE'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
if report.get("rotation", {}).get("blocked"):
    raise SystemExit("source rotation blocked; preserving previous snapshot")
PYROTATE
python3 "$REPO_ROOT/scripts/merge_external_verified.py" \
  --validated "$TMP_DIR/validated-uris.json" \
  --intelligence "$TMP_DIR/feed/telegram-source-intelligence.json" \
  --state-dir "$STATE_DIR"
cp "$TMP_DIR/feed/telegram-source-intelligence.json" "$STATE_DIR/latest-report.json"
chmod 0600 "$STATE_DIR/latest-report.json"
python3 "$REPO_ROOT/scripts/telegram_config_engine.py" \
  --validated-uris "$TMP_DIR/validated-uris.json" \
  --intelligence-report "$TMP_DIR/feed/telegram-source-intelligence.json" \
  --output "$TMP_DIR/feed/telegram-all.txt" \
  --manifest "$TMP_DIR/feed/telegram-all.json"

# Publish a single-root snapshot. Force-updating config-feed replaces the previous
# snapshot rather than accumulating old profiles or history.
git -C "$TMP_DIR" init -b feed-snapshot >/dev/null
git -C "$TMP_DIR" config user.name "AICP VPN Feed Publisher"
git -C "$TMP_DIR" config user.email "aicp-vpn-feed@users.noreply.github.com"
git -C "$TMP_DIR" add feed/telegram-all.txt feed/telegram-all.json feed/telegram-source-intelligence.json
git -C "$TMP_DIR" -c commit.gpgsign=false commit -m "chore(vpn-feed): refresh rolling Telegram snapshot" >/dev/null
git -C "$TMP_DIR" remote add origin git@github.com:faridbarahimi/AICP-VPN.git
git -C "$TMP_DIR" push --force origin HEAD:refs/heads/config-feed

# Invalidate both public jsDelivr edges so clients do not keep a successful but stale 200 response.
for file in telegram-all.txt telegram-all.json telegram-source-intelligence.json; do
  if ! curl -fsS --max-time 20 "https://purge.jsdelivr.net/gh/faridbarahimi/AICP-VPN@config-feed/feed/$file" >/dev/null; then
    printf 'WARNING: jsDelivr cache purge failed for %s; origin snapshot is still published\\n' "$file" >&2
  fi
done

printf 'Published rolling Telegram snapshot: '
grep '^# generated_at=' "$TMP_DIR/feed/telegram-all.txt"
grep '^# config_count=' "$TMP_DIR/feed/telegram-all.txt"
