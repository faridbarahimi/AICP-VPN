#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/xray-aar.lock.properties"
TARGET="$ROOT/app/libs/libv2ray.aar"
property() { awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK"; }
[[ -s "$LOCK" ]] || { echo "Missing Xray artifact lock: $LOCK" >&2; exit 1; }
URL="$(property release_url)"; EXPECTED="$(property aar_sha256)"
[[ "$URL" == https://github.com/* && "$EXPECTED" =~ ^[0-9a-fA-F]{64}$ ]] || { echo "Invalid pinned Xray URL/hash" >&2; exit 1; }
if [[ -f "$TARGET" ]]; then
  actual="$(sha256sum "$TARGET" | awk '{print $1}')"
  [[ "$actual" == "$EXPECTED" ]] || { echo "Existing AAR hash mismatch; refusing overwrite" >&2; exit 1; }
  echo "Pinned Xray AAR already present and verified."; exit 0
fi
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
curl -fsSL --retry 3 --retry-delay 5 --max-time 300 "$URL" -o "$work/libv2ray.aar"
actual="$(sha256sum "$work/libv2ray.aar" | awk '{print $1}')"
[[ "$actual" == "$EXPECTED" ]] || { echo "Downloaded AAR hash mismatch; refusing install" >&2; exit 1; }
install -D -m 0644 "$work/libv2ray.aar" "$TARGET"
echo "Pinned Xray AAR fetched and verified."
