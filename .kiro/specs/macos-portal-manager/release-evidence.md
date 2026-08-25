# Portal Manager Release Evidence

Status: incomplete. This record tracks the current candidate and the exact
validation that remains before release.

## Candidate

- Branch: `feature/portal-manager-hardening`
- Head: `1100926`
- Base: `ae46e16` (`v1.73`)
- Android Release APK: `app/build/outputs/apk/release/app-release.apk`
- APK SHA-256:
  `35dfaf6d94dcbfd626d653f69b1c0c4ba446e065361c44ffbed3c907edc2f6e5`
- Signing certificate SHA-256:
  `a0bdd0ab4a888d8d9ca31e78fd065bd6273c0d740672352c52900316df7bbbb0`
- macOS signed/notarized artifact: pending operator signing identity and
  Apple Notary submission.

## Automated gates

- Android JVM unit tests: pass.
- `fleetctl` Rust unit tests: pass (8 tests).
- Background Service Cargo tests: pass (6 tests).
- Portal Manager scope check: pass.
- Portal Manager direct XCTest bridge: pass (178 tests).
- Offline Release verification tool check: pass.
- Workflow YAML parse: pass.
- Working tree at the recorded head: clean.

The CI workflow has not run yet because upstream push access is unavailable.

## Live Fleet baseline

Recorded on 2026-08-24 over local LAN with authenticated Fleet requests.

| Device | Serial | Endpoint | Result |
|---|---|---|---|
| Portal Mini | `819LCM01Z09E4D12` | `10.0.0.245:8723` | Authenticated; API 29; Immortal 1.73 (67); install mode dialog; present. |
| Portal Mini 2 | `819LCM02Z100PQ21` | `10.0.0.164:8723` | Registered token rejected (`unauthorized`). |
| Portal Plus | `818PGA02P113KS20` | `10.0.0.151:8723` | Authenticated; API 28; Immortal 1.60 (54); paused legacy installer; absent. |

mDNS discovery found all three `_immortal-remote._tcp` services.

Both authenticated devices returned `not_found` for `GET /apps/profile`, which
is the expected baseline because they have not received the candidate build.

## Casting receiver inventory

Local Bonjour discovery found usable receiver candidates on interface 14:

- AirPlay: `Simon's Fire TV`, TCL model `55QM64L`, endpoint
  `localhost.local.:7000`.
- Chromecast: Google Home service ending `fdd35`, endpoint port `8009`,
  friendly name `Kitchen speaker`.

These are discovery targets only. They are not playback proof.

## Required live workflow evidence

1. Deploy the candidate to Portal Mini with explicit operator approval.
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

- Upstream GitHub push is denied for the current credentials.
- Apple signing identity and authorized Notary submission are unavailable.
- Device deployment and mutation workflows require explicit operator approval.
