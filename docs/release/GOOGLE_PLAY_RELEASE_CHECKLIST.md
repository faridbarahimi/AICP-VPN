
> [!important] SINGLE ACTIVE TASK LEDGER
> This document is retained as a reference/evidence snapshot, not as an active tracker. Record task status only in [[10-Knowledge/EN/17 - Master Checklist (Evidence, EN)/Master-Checklist.md|چک‌لیست جامع اجرایی]]. Do not treat the historical checkboxes below as current status.

# AICP VPN — Google Play release packet

## Build identity

- Product name: AICP VPN
- Application ID: `com.aicp.vpn`
- Version: `2.3.12` (`versionCode=752`)
- Minimum Android API: 29
- Target Android API: 37
- Play upload format: signed Android App Bundle (`.aab`)
- Upload signing alias: `aicp-release` (private key stays on the build host; do not publish it)

## Before uploading to Play Console

- [ ] Create the AICP VPN app record under the correct Play Console developer account. The new application ID is a new app identity; it does not update the old `com.patterniha.pattng.fdroid` installation.
- [ ] Enable Play App Signing and register the upload certificate shown in the release report.
- [ ] Publish a real privacy-policy URL and make sure the policy matches actual collection, diagnostics, IP-geolocation checks, and public-config source fetching.
- [ ] Complete the Data safety form based on the final implementation and SDK inventory; do not guess answers.
- [ ] Complete the VPN-service declaration and any prominent disclosure/consent requirements that apply. Explain that a VPN connection routes network traffic through the selected endpoint.
- [ ] Add store listing short/full descriptions, support contact, app category, content rating, target audience, and country availability.
- [ ] Add phone screenshots and review them on a physical Android device. The supplied icon and feature graphic are only two of the required store assets.
- [ ] Test fresh install, Android VPN consent, connect/disconnect, notification controls, per-app routing, config import/export, Frankfurt target verification, and network loss on a physical device.
- [ ] Verify that a connected endpoint actually exits in Frankfurt using the in-app AICP Network Identity check. A Germany-labelled public feed is only a discovery hint and cannot guarantee a Frankfurt exit.
- [ ] Review all open-source notices and ensure the source distribution retains applicable licenses and required notices.

## Important behavior

At first launch, AICP initializes the desired exit target to Germany → Hesse → Frankfurt am Main and automatically starts the Germany public-config finder when internet access is available. The finder imports candidates and runs real connection/latency qualification (failed, unmeasured, or >300 ms entries are pruned from the dedicated AICP country bank). It does **not** silently connect the VPN: Android VPN consent and the user's connection action remain required. A target location is not proof of the live public IP location.

## Current release gate

A successful build and signature verification are necessary but not sufficient for Play approval. Play Console declarations, a hosted privacy policy, physical-device QA, and account-specific listing details must be completed before submission.

## Upload certificate fingerprint

SHA-256: `34:7D:15:F0:99:2B:B0:18:A5:ED:2C:FE:9E:D0:46:DA:F1:7F:6B:13:2B:53:AF:4E:8C:19:4E:C9:46:08:42:2A`

Use this upload certificate when configuring Play App Signing for this new application ID. The keystore and passwords are not included in this packet.
