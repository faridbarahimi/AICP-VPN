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
python3 "$REPO_ROOT/scripts/telegram_config_engine.py" \
  --sources "$REPO_ROOT/config/telegram-sources.json" \
  --output "$TMP_DIR/feed/telegram-all.txt" \
  --manifest "$TMP_DIR/feed/telegram-all.json"

# Publish a single-root snapshot. Force-updating config-feed replaces the previous
# snapshot rather than accumulating old profiles or history.
git -C "$TMP_DIR" init -b feed-snapshot >/dev/null
git -C "$TMP_DIR" config user.name "AICP VPN Feed Publisher"
git -C "$TMP_DIR" config user.email "aicp-vpn-feed@users.noreply.github.com"
git -C "$TMP_DIR" add feed/telegram-all.txt feed/telegram-all.json
git -C "$TMP_DIR" -c commit.gpgsign=false commit -m "chore(vpn-feed): refresh rolling Telegram snapshot" >/dev/null
git -C "$TMP_DIR" remote add origin git@github.com:faridbarahimi/AICP-VPN.git
git -C "$TMP_DIR" push --force origin HEAD:refs/heads/config-feed

printf 'Published rolling Telegram snapshot: '
grep '^# generated_at=' "$TMP_DIR/feed/telegram-all.txt"
grep '^# config_count=' "$TMP_DIR/feed/telegram-all.txt"
