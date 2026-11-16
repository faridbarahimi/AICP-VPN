#!/usr/bin/env bash
set -euo pipefail

VERSION="1.14.4"
ARCHIVE="sing-box-${VERSION}-linux-arm64.tar.gz"
EXPECTED_SHA256="436441f76ee2f7fe21b5b2d426d3cb261a480455a704865789ad533e4273ec26"
DEST="/opt/aicp-vpn-tools/sing-box-${VERSION}"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "aarch64" ]]; then
  echo "This pinned installer currently supports Linux ARM64 only." >&2
  exit 2
fi
if [[ -x "${DEST}/sing-box" ]] && "${DEST}/sing-box" version | grep -q "sing-box version ${VERSION}"; then
  echo "sing-box ${VERSION} already installed"
  exit 0
fi
TMP="$(mktemp -d /tmp/aicp-sing-box-install.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 --max-time 180 -o "${TMP}/${ARCHIVE}" \
  "https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/${ARCHIVE}"
echo "${EXPECTED_SHA256}  ${TMP}/${ARCHIVE}" | sha256sum -c -
tar -xzf "${TMP}/${ARCHIVE}" -C "${TMP}" --strip-components=1 "sing-box-${VERSION}-linux-arm64/sing-box"
install -d -m 0755 "${DEST}"
install -m 0755 "${TMP}/sing-box" "${DEST}/sing-box.new"
mv "${DEST}/sing-box.new" "${DEST}/sing-box"
"${DEST}/sing-box" version
sha256sum "${DEST}/sing-box"
