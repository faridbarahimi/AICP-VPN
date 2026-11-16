# SRCVPN NPV attachment source

`@srcvpn` is enabled in `config/telegram-sources.json` as a public Telegram source. The ordinary URI feed collector treats it as an attachment-only source when its public preview contains no VLESS/Trojan/etc. URI, so it cannot block publication from the other sources.

The source catalog declares `.npvt` and `.npvs` as attachment extensions. `scripts/telegram_npv_attachment_index.py` indexes only public Telegram document metadata (channel, message URL, filename, extension, and displayed size). It does not download encrypted payloads, decrypt them, or publish them into the public URI feed.

The daily source-intelligence systemd service runs this indexer after its main job and writes the private report to:

`/var/lib/aicp-vpn-source-intelligence/npv-attachment-index.json`

The report is protected by the service's `UMask=0077`. The Android app's local NPV import remains the supported path for decrypted profile import: the user downloads the attachment from Telegram and opens it in AICP. Automatic background download/import is intentionally not enabled.
