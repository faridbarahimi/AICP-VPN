#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/native-runtime-assets.sha256"
BUNDLE_URL="${AICP_VPN_NATIVE_ASSET_BUNDLE_URL:-https://github.com/faridbarahimi/AICP-VPN/releases/download/v2.3.27/native-runtime-assets-2.3.27.tar.gz}"
[[ -s "$MANIFEST" ]] || { echo "Missing native asset pin manifest: $MANIFEST" >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL --retry 3 --retry-delay 5 --max-time 600 "$BUNDLE_URL" -o "$work/native-runtime-assets.tar.gz"
# Reject absolute paths and traversal before extraction.
if tar -tzf "$work/native-runtime-assets.tar.gz" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "Unsafe path in native runtime bundle" >&2; exit 1
fi
tar -xzf "$work/native-runtime-assets.tar.gz" -C "$work"
while read -r expected relative; do
  [[ -n "$expected" ]] || continue
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ && "$relative" == app/libs/* ]] || {
    echo "Malformed or out-of-scope native asset pin: $expected $relative" >&2; exit 1;
  }
  source="$work/$relative"
  target="$ROOT/$relative"
  [[ -s "$source" ]] || { echo "Missing bundle member: $relative" >&2; exit 1; }
  actual="$(sha256sum "$source" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || { echo "Hash mismatch in bundle: $relative" >&2; exit 1; }
  mkdir -p "$(dirname "$target")"
  install -m 0755 "$source" "$target"
done < "$MANIFEST"
(cd "$ROOT" && sha256sum -c native-runtime-assets.sha256)
echo "Pinned native runtime assets fetched and verified."
