# Portal Manager Release Evidence

Status: incomplete. This record tracks the current candidate and the exact
validation that remains before release.

## Candidate

- Branch: `feature/portal-manager-hardening`
- Implementation head: `2c0ea0d` (later commits are evidence-only).
- Base: `ae46e16` (`v1.73`)
- Android Release APK: `app/build/outputs/apk/release/app-release.apk`
  Built artifact SHA-256 at validation:
  `cff3fe28a849190f4af425bd18e5282d7e4885d79fe65661a61543efe1add4b9`
- Android Release packaging omits VCS HEAD metadata. APK signing remains
  nondeterministic because the configured RSA key uses APK Signature Scheme v2;
  verify the exact artifact by digest immediately before deployment.
- Signing certificate SHA-256:
  `a0bdd0ab4a888d8d9ca31e78fd065bd6273c0d740672352c52900316df7bbbb0`
- macOS signed/notarized artifact: pending operator signing identity and
  Apple Notary submission.

## Automated gates

- Android JVM unit tests: pass.
- Android signed Release build: pass.
- Android Release deployment preflight: pass (identity, signer, v2 scheme, and
  artifact digest).
- `fleetctl` Rust unit tests: pass (9 tests), including SHA-256 boundary vectors.
- Background Service Cargo tests: pass (6 tests, locked).
- Portal Manager scope check: pass.
- Portal Manager direct XCTest bridge: pass (178 tests).
- Offline Release verification tool check: pass.
- Workflow YAML parse: pass.
- CI now includes macOS scope/build/tests, Rust tests, and Android unit tests.
  Upstream PR [starbrightlab/immortal#224](https://github.com/starbrightlab/immortal/pull/224)
  is clean and mergeable at `2c0ea0d`; its `portal-manager`, `rust-tests`,
  `unit-tests`, and `version-sync` checks all pass in workflow runs
  `32908260198` and `32908260199`. Signed Release packaging remains a local
  gate because public runners do not have the protected signing key.

## Live Fleet baseline

Refreshed on 2026-08-25 over local LAN with authenticated Fleet requests.
mDNS found all three `_immortal-remote._tcp` services. ADB reported no USB
devices.

| Device | Serial | Endpoint | Result |
|---|---|---|---|
| Portal Mini | `819LCM01Z09E4D12` | `10.0.0.245:8723` | Authenticated; API 29; Immortal 1.73 (67); install mode dialog; present. |
| Portal Mini 2 | `819LCM02Z100PQ21` | `10.0.0.164:8723` | Registered token rejected (`unauthorized`). |
| Portal Plus | `818PGA02P113KS20` | `10.0.0.151:8723` | Authenticated; API 28; Immortal 1.60 (54); paused legacy installer; absent. |

Both authenticated devices returned `not_found` for `GET /apps/profile`, which
is the expected baseline because they have not received the candidate build.

Portal Mini also returned a successful sanitized diagnostics snapshot: the
root filesystem was 95% used and userdata was 48% used.

## Casting receiver inventory

On 2026-08-24, local Bonjour discovery found usable receiver candidates on
interface 14:

- AirPlay: `Simon's Fire TV`, TCL model `55QM64L`, endpoint
  `localhost.local.:7000`.
- Chromecast: Google Home service ending `fdd35`, endpoint port `8009`,
  friendly name `Kitchen speaker`.

These are discovery targets only. They are not playback proof.

A 2026-08-25 read-only refresh again found the Chromecast service ending
`fdd35`, plus additional Cast receivers and a Cast group. It did not find the
previously recorded Fire TV under `_airplay._tcp`; it found `Simon's Mac mini`
and `Simon's MacBook Pro` instead. Receiver visibility changes do not provide
playback proof, and no connection or playback was started during discovery.

## Required live workflow evidence

1. Run `scripts/verify-android-release.sh` with version `67`, name `1.73`, the
   recorded certificate and APK digests, then deploy that exact APK to Portal
   Mini with explicit operator approval using `fleetctl dev update --sha256`.
2. Verify `/apps/profile` discovery, set/install, retry reporting, state
   transition, and profile removal.
3. Exercise credential sharing between authenticated Portals.
4. Exercise volume up/down/mute and media previous/play-pause/next.
5. Establish a Room Link session and capture both endpoints' sanitized state.
6. Connect and play/stop one real AirPlay target.
7. Connect and play/stop one real Chromecast target.
8. Build and verify a Developer ID signed, notarized, stapled Portal Manager
   artifact with `macos/package-release.sh`.

## Release blockers

- Upstream review and merge of PR #224 are required.
- Apple signing identity and authorized Notary submission are unavailable.
- Device deployment and mutation workflows require explicit operator approval.
