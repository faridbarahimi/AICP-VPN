#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_MOBILE_VERSION="v0.0.0-20260908204917-8b95e45f8d3e"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME" ]] || { echo "ANDROID_HOME must point to an installed Android SDK" >&2; exit 1; }
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  latest_ndk="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  [[ -n "$latest_ndk" ]] || { echo "Install an Android NDK and set ANDROID_NDK_HOME" >&2; exit 1; }
  export ANDROID_NDK_HOME="$latest_ndk"
fi
command -v go >/dev/null || { echo "Go 1.27.4 is required" >&2; exit 1; }
[[ "$(go env GOVERSION)" == "go1.27.4" ]] || { echo "Expected Go 1.27.4, got $(go env GOVERSION)" >&2; exit 1; }
[[ "$(go env GOHOSTARCH)" == "amd64" ]] || { echo "gomobile Android AAR build currently requires an x86_64 host; use the Android NPV GitHub Actions workflow from ARM64 machines." >&2; exit 1; }
command -v gomobile >/dev/null || go install "golang.org/x/mobile/cmd/gomobile@$GO_MOBILE_VERSION"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init
mkdir -p "$ROOT/app/libs"
(
  cd "$ROOT/AndroidLibPantegnos"
  gomobile bind -target=android -androidapi=29 -javapkg=com.aicp.vpn.npv \
    -o "$ROOT/app/libs/aicpnpv.aar" ./npv
)
[[ -s "$ROOT/app/libs/aicpnpv.aar" ]] || { echo "gomobile did not produce aicpnpv.aar" >&2; exit 1; }
sha256sum "$ROOT/app/libs/aicpnpv.aar"
echo "Built local NPVT/NPVS AAR: app/libs/aicpnpv.aar"
