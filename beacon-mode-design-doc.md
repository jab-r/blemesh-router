# Plan: Beacon-Mode Design Doc for Team Review

> **STATUS (June 2026): built.** The artifact is `docs/beacon-mode.md` (its open
> questions are now locked there). The thread continued into
> `docs/location-customization.md`, which positions beacon mode as the offline
> trigger for location switching. This file is the original build-plan, kept for
> history.

## Context

The blemesh-router currently advertises only its mesh GATT service UUID (`F47B5E2D-…`) and is invisible to the loxation client beacon scanners (`loxation-android` and `loxation-sw`). Making each router discoverable as a registerable BLE beacon — with a fixed, server-registered UUID per device — lets the existing loxation client base sense router presence without any client-side change, and gives operations a clean per-device identity to register.

This task is **doc-only**: produce a design markdown for the team to review before any code lands. Implementation is a follow-up.

## Deliverable

A single new markdown file at **`docs/beacon-mode.md`** (creates the `docs/` folder).

No code changes in this task. The markdown is the artifact.

## Design decisions to lock in (already agreed)

- **Format**: generic manufacturer data (HolyIOT-style), 16-byte ID. Smallest on-air payload (~20 B), and the loxation scanner's generic path formats the last 16 bytes of any non-Apple manufacturer data as a UUID — exact match for what we want.
- **Identity model**: one fixed 16-byte UUID per device, generated once at first launch, persisted forever. **Independent of `PeerID`** — PeerID stays the 8-byte mesh-layer ID; the beacon UUID is the registered server-side identity. No coupling between them in this iteration.
- **Registration**: manual, out-of-band. Operator reads the UUID off the device's settings screen and registers it on the server. No self-registration endpoint.
- **Activation**: always-on while the mesh service runs (no user toggle for now).
- **Coexistence with the mesh service UUID**: the existing 128-bit mesh service UUID stays in the primary advertisement; the new manufacturer-data element goes in the **scan response** (free 31-byte budget). One `startAdvertising(settings, advertiseData, scanResponse, callback)` call. No extended-advertising / no API-level bump.
- **Company ID**: `0xFFFF` (reserved-for-testing / no-company). The loxation generic path doesn't filter on company ID.

## Markdown structure (what `docs/beacon-mode.md` should contain)

1. **Goal & motivation** — make routers visible to existing loxation clients as registerable beacons; bridge sighting data without changing client code.
2. **Format choice** — table comparing generic mfr data, iBeacon, Eddystone-UID; rationale for generic.
3. **Identity & persistence** — 16-byte UUID, random at first launch, stored in `SharedPreferences` (existing `"router"` prefs file, new key `beacon_uuid`). Mirrors the pattern in `app/src/main/java/com/blemesh/router/router/LocalIdentity.kt`.
4. **Advertising payload** — exact byte layout of the manufacturer-data AD element (`[len][0xFF][FF FF][16-byte UUID]`); placement in scan response; preserved primary advertisement.
5. **Settings-UI surfacing** — display UUID as monospaced text with a Copy button; optional QR code (deferred).
6. **Manual registration workflow** — operator opens settings, copies UUID, registers on server.
7. **Loxation client side (informational, no changes needed)** — pointer to `BeaconScanManager.kt:214–345` (Android) and `BeaconScanManager.swift:1171–1238` (iOS) showing the generic path that already picks this up.
8. **Open questions for the team**:
   - Eventual move to iBeacon if a fleet-wide UUID with major/minor becomes preferable to per-device registration?
   - Future self-registration endpoint?
   - Should the beacon UUID survive a "reset router identity" action, or be regenerated alongside PeerID?
9. **Out of scope** — actual code changes; provisioning automation; beacon ranging/RSSI calibration (we set no TX-power field in the generic format).

## Files to be modified later (not in this task, listed for the team's reference)

- `app/src/main/java/com/blemesh/router/mesh/BleMeshService.kt` (`startAdvertising` at lines 566–584; call at `:579`) — add scan-response with manufacturer data.
- `app/src/main/java/com/blemesh/router/router/LocalIdentity.kt` — extend (or add sibling `BeaconIdentity.kt`) to generate and persist the 16-byte UUID.
- Settings/UI screen (TBD which Compose screen) — display UUID + Copy.

## Verification

After writing `docs/beacon-mode.md`:

1. Open the file, confirm it renders cleanly in a Markdown viewer.
2. Confirm the listed file:line references match the current codebase (`BleMeshService.kt:250`, `LocalIdentity.kt`, loxation scanner paths).
3. Share the file path with the team for review. No build/test verification needed — doc-only change.
