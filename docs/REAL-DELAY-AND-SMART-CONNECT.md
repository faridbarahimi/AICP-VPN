# Real Delay, Payload Verification and Smart Connect

## Why a delay result alone was insufficient

The Xray native delay routine issues HTTP GET requests and accepts HTTP 200 or
204. It drains a response body when present, but 204 has no body and a tiny/empty
200 can still be accepted. Therefore a positive delay alone was not sufficient
proof that a candidate can transfer useful application data.

## New candidate qualification

`RealPingWorkerService` now uses two sequential stages through the exact
candidate outbound:

1. Latency probe against the configured primary URL, then configured fallback.
2. A cache-busted 32 KiB download from Cloudflare Speed Test; if unavailable,
   a 32 KiB httpbin response is tried. A candidate is marked reachable only if
   the native outbound request completes successfully. The native routine
   drains the complete response body before returning.

Every positive native Xray candidate now opens the exact profile outbound as a
local SOCKS exit and performs an OkHttp transfer through it. The probe requires
HTTP 200, non-HTML content and at least 16 KiB actually read; HTTP 204,
redirects, empty/tiny responses and captive-portal HTML fail. Aether-only and
Aether-chained candidates use their exact Aether SOCKS port for the same
body-bearing test. The process-wide Xray exit is protected by a fair semaphore
so concurrent workers cannot replace or close each other's exits.

The candidate timeout is bounded at 16 seconds for native Xray profiles and 14
seconds for Aether. Cancellation still cancels the native probe batch; a slow
profile is skipped rather than stalling the whole batch.

## Live Smart Connect monitor

The connected-session monitor is separate from batch candidate testing. It
binds the probe to Android's VPN `Network` (not the app's loopback SOCKS proxy)
and now requires at least 16 KiB of HTTP 200 non-HTML payload. It tries three
independent HTTPS payload hosts within a single 5-second absolute deadline, splitting time fairly so one blackholed host cannot prevent the fallback from being tried.

Smart Connect behavior in the code:

- Runs only while Smart Connect is enabled and the VPN session is running.
- Probes every 60 seconds while healthy; every 5 seconds during recovery.
- Requires an unhealthy condition to persist for 10 seconds, and requires a
  successful direct-network probe before latency-failure failover.
- A speed-based recovery requires three degraded traffic samples, with a
  minimum direct speed and a material tunnel/direct gap.
- Candidate selection is limited to the user's selected country/list, except
  the explicit Multi-Location aggregate.
- It does not continuously rotate profiles on a timer. It switches only after
  a sustained health/speed failure and a successful candidate test.

## Router vs. profile rotation

`RootProxyManager` is the Android Root-mode packet router (HEV/tun2socks,
iptables/ip6tables, policy routing and DNS capture). It routes packets through
the currently selected tunnel; it is not itself the profile-rotation engine.
Smart Connect is the profile recovery/selection controller. Source inspection
can confirm these code paths and unit policies, but cannot prove that the
user's phone currently has Root mode active or that Smart Connect is enabled;
that requires live device telemetry/acceptance testing.

## VASLBOX source status

The public `@VASLBOX` page is a backup/invite pointer and currently directs users
to the invite-only main channel `https://t.me/+Fi8jnlNsCt5kNGJk`. The public
backup page does not expose config attachments in its preview. It is catalogued
with keep-retention for discovery, but no inaccessible private-channel files
are fabricated or scraped. The invite-only main channel can be enabled as a
config source only after authorized membership/access is provided to the
collector.

## OpenVPN and NPV file handoff

The local file picker now recognizes `.ovpn`, `.npvt` and `.npvs` by filename
and routes them to external Android handlers using the original SAF content URI
with read permission. `.ovpn` uses the standard `application/x-openvpn-profile`
MIME type. This delegates import to an installed OpenVPN client; AICP does not
yet run an OpenVPN engine itself, and these external profiles are not managed
by AICP Smart Connect or its profile rotation.

NPVT/NPVS are NapsternetV formats. I checked Hiddify's public source/issues:
its issue #2267 requests import/export of locked NPV Tunnel profiles and was
closed as not planned; I found no official `.npvt` parser in the public source.
If a particular Hiddify build accepts it, that behavior is not represented in
the public repository evidence and needs a sample/version-specific check.

The discovered third-party decrypter reports white-box AES lookup tables and
multiple NPVS envelope generations; its repository has no declared reuse
license. AICP therefore does not bundle or copy its code or cryptographic
tables, and does not upload private configuration files to a third-party
converter. The picker offers the file to installed handlers using both the
specific binary MIME type and a wildcard fallback. Direct in-app decryption/
conversion remains pending a clear license or independent format specification
and representative test files.

Upstream references:
- Hiddify NPV locked-profile import request (closed as not planned): https://github.com/hiddify/hiddify-app/issues/2267
- NPVT/NPVS decrypter README (no license file found): https://github.com/aminbaghaeidev/NPVT-NPVS-Decrypter
- OpenVPN Android MIME/API reference: https://github.com/schwabe/ics-openvpn
- OpenVPN Connect profile import: https://openvpn.net/connect-docs/import-profile/

## Anti-oscillation and runtime evidence

Smart Connect's source policy currently uses a 60-second healthy probe cadence,
a 5-second recovery cadence, a 5-second absolute payload-probe budget, and a
10-second sustained-unhealthy threshold. Recovery is rate-limited to one action
per 30 seconds and at most three recoveries per rolling 10-minute window. A
speed-based recovery requires three degraded traffic samples; candidates are
selected only from the currently selected group/list (Multi-Location is the
explicit aggregate exception).

`RootProxyManager` installs/removes owned iptables/ip6tables and policy-routing
rules and manages the `hev-socks5-tunnel` helper. It is the packet-routing
component, not the profile-selection loop. The code can be inspected and unit
tested, but this VPS has no attached Android device/ADB telemetry in this run.
Therefore whether Root mode is active on the user's phone, whether the VPN
service is currently connected, and whether packets are actually traversing
the router remain UNKNOWN until checked on-device. No claim of live router
operation is made from source inspection alone.
