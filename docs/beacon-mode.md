# Beacon Mode: Router as a Registerable Fixed Beacon

**Status: DESIGN — for team review. No code has landed.**
June 2026. Companion plan: `beacon-mode-design-doc.md` (repo root). Beacon mode is
the **offline trigger** for location switching — see `docs/location-customization.md`.

## 1. Goal & motivation

The router currently advertises only the mesh GATT service UUID
(`F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5D`) and is invisible to the loxation
client beacon scanners. Making each router discoverable as a registerable BLE
beacon — with a fixed, server-registered UUID per device — lets the existing
loxation client base (loxation-android + loxation-sw) sense router presence
**without any client-side change**, and gives operations a clean per-device
identity to register.

Routers are physically fixed infrastructure (Wi-Fi access points), which makes
them natural anchors for the loxation indoor-positioning beacon model: a
registered router sighting is a location fix the same way any registered
beacon sighting is.

**Relationship to the announce nodeType TLV** (`NODETYPE_RELAY_ONLY_SPEC.md`):
complementary halves of "the router is fixed infrastructure". Beacon mode is
the *advertising-layer* half — it makes the router sightable as a registered
venue beacon with zero client changes, but doesn't touch the mesh announce.
The nodeType TLV (0x08 `fixedRelay`) is the *mesh-layer* half — it stops
phones from initiating doomed Noise handshakes toward the router, but isn't
visible to the beacon scanners. A future iteration can correlate the two
identities (beacon UUID ↔ relay-only mesh peer) for fixed-anchor indoor
positioning; in this iteration they remain independent (Open Question 5).

## 2. Format choice: generic manufacturer data

| | Generic mfr data | iBeacon | Eddystone-UID |
|---|---|---|---|
| On-air size (AD element) | **20 B** | 27 B | ~22 B (service data) |
| Client support today | ✔ generic path, both platforms | ✔ | ✔ |
| Identity granularity | 16-byte UUID per device | UUID + major/minor (fleet model) | 10-byte namespace + 6-byte instance |
| License / spec constraints | none | Apple iBeacon license, fixed layout | Google spec (frozen) |
| iOS background ranging | ✘ (foreground scan only) | ✔ via CoreLocation | ✘ |

**Chosen: generic manufacturer data (HolyIOT-style), 16-byte ID.** Smallest
payload, zero client work, no license. The loxation generic scanners format
the last 16 bytes of any non-Apple manufacturer data as an uppercase dashed
UUID — exactly what we emit.

iBeacon is the documented fallback if iOS *background* sighting of routers
ever becomes a requirement (see Open Questions): CoreLocation region
monitoring is the only background-capable path on iOS, and it requires the
iBeacon format and a fleet-wide UUID with major/minor per device.

## 3. Identity & persistence

- One fixed **16-byte beacon UUID per device**, generated from `SecureRandom`
  at first launch, persisted forever.
- **Independent of `PeerID`.** PeerID stays the 8-byte mesh-layer identity
  derived from the Noise static key; the beacon UUID is the registered
  server-side identity. No coupling in this iteration — re-keying the mesh
  identity does not change the registered beacon, and vice versa.
- Storage: the existing `"router"` `SharedPreferences` file (the one
  `LocalIdentity` uses, `router/LocalIdentity.kt:32`), new key `beacon_uuid`.
  Same generate-once-then-load pattern as the Noise/signing keys.

## 4. Advertising payload

The existing primary advertisement is untouched; the beacon element rides in
the **scan response**, which is currently unused (31-byte budget entirely
free).

Scan-response AD element:

```
[0x13] [0xFF] [0xFF 0xFF] [b0 .. b15]
 len    type   company ID   16-byte beacon UUID
 (19)   (mfr   (0xFFFF,
        data)  little-endian on air)
```

20 bytes total — fits legacy advertising with 11 bytes to spare. No extended
advertising, no minSdk/API-level change.

Implementation point: `mesh/BleMeshService.kt:566-584` (`startAdvertising`; the
`startAdvertising(...)` call is at `:579`) switches from the 3-arg
`startAdvertising(settings, data, callback)` to the
4-arg overload with a `scanResponse` built via
`AdvertiseData.Builder().addManufacturerData(0xFFFF, beaconUuidBytes)`.
Android's `addManufacturerData` writes the company ID itself; we supply
exactly the 16 UUID bytes.

Activation: always-on while the mesh service runs. No user toggle in this
iteration.

## 5. Settings-UI surfacing

Display the beacon UUID in the router's config screen
(`ui/ConfigActivity.kt`) as monospaced text with a **Copy** button. QR code
rendering: deferred (nice-to-have, not required for manual registration).

## 6. Registration workflow (manual, out-of-band)

1. Operator installs/launches the router; the beacon UUID is generated and
   persisted on first launch.
2. Operator opens the router's settings screen, copies the UUID.
3. Operator registers the UUID on the loxation server as a fixed beacon at
   the router's installed location.
4. Loxation clients near the router begin reporting sightings with no app
   update.

No self-registration endpoint in this iteration.

## 7. Loxation client side — verified, no changes needed (June 2026)

**loxation-android** (`service/BeaconScanManager.kt`):

- Scans **unfiltered** (`filters = emptyList()`, `SCAN_MODE_LOW_LATENCY`,
  `MATCH_MODE_AGGRESSIVE`) — scan responses are received and merged into the
  `ScanRecord`.
- `parseGenericBeacon` takes the first **non-Apple** manufacturer-data entry
  (only Apple's company ID is skipped — `0xFFFF` passes), and for payloads
  ≥ 16 bytes formats the **last 16 bytes** as an uppercase dashed UUID.
  Android's `SparseArray` strips the company ID, so our payload is exactly
  the 16 UUID bytes — `last 16 == whole payload`.

**loxation-sw** (`Services/BeaconScanManager.swift`, scan started from
`BLEMeshService.swift:1800-1803`):

- Beacon-scanning mode scans `withServices: nil` (all devices); CoreBluetooth
  active scanning merges the scan response into `advertisementData`, so the
  manufacturer data is visible.
- The generic path (`BeaconScanManager.swift:1152-1167`) formats
  `manufacturerData.suffix(16)` as an uppercase dashed UUID — identical
  derivation to Android.

Both clients therefore compute the **same UUID string** from our element,
which is what gets registered server-side.

> Note: *sighting* the beacon needs no client change, but turning a sighting into
> anything useful (a location / venue customization) is a **separate concern** —
> today it's an online-only server round-trip, and offline support is the subject
> of `docs/location-customization.md`. Beacon mode supplies the breadcrumb; that
> doc supplies the resolution + offline cache.

Known platform limits (pre-existing properties of the clients' generic
scanning, not introduced by this design):

- **iOS sees the beacon in foreground only** — `withServices: nil` scans do
  not run in background. iBeacon is **not** a clean fallback here: loxation-sw's
  CoreLocation ranging path (`BeaconScanManager.swift:944-983`) rings a *fixed
  known-UUID set* under `requestWhenInUseAuthorization` (foreground only), so iOS
  background router-sighting needs **loxation-sw client changes** (Always auth +
  region monitoring + the router UUID in the ranged set) — not a router-side
  format change. See Decision 1.
- **Android unfiltered scans are throttled when the screen is off**
  (OS-level, Android 8.1+). Sighting cadence degrades but does not stop.
- Distance estimation for generic beacons is crude on both platforms (no TX
  power field in the generic format; Android uses `|rssi|/10`). Acceptable:
  the registered-sighting model needs presence, not ranging.

## 8. Decisions (locked, June 2026)

1. **Format — generic, no iBeacon.** Stay on generic manufacturer data.
   iBeacon would buy **no** iOS background sighting without loxation-sw client
   changes: its CoreLocation path (`BeaconScanManager.swift:944-983`) rings a
   *fixed known-UUID set* under `requestWhenInUseAuthorization` (foreground
   only). There is no zero-client-change background path on iOS in any format,
   so iOS background sighting is a separate loxation-sw project, not a router
   format choice.
2. **Self-registration — deferred (manual for now).** A feasible path exists
   (Firebase **anonymous** auth + `POST /auth/register-device`, which
   auto-creates a beacon record), but it needs the loxation Firebase config in
   the router, a `fixed` beacon type server-side (the endpoint hardcodes
   `mobile`), and clearing `packageName`/Play-Integrity gating. Out of scope
   this iteration; revisit as a scoped follow-up. If built, register
   `deviceId = the 16-byte beacon UUID`.
3. **Identity-reset — the beacon UUID survives** a mesh PeerID re-key. The
   registration represents the physical install location, which doesn't change.
4. **Company ID — keep `0xFFFF` for the pilot.** Works on both clients today;
   revisit the "reserved for internal testing" non-compliance before wide
   deployment.
5. **Beacon UUID ↔ location/mesh correlation — required for offline, not just
   nice-to-have.** Offline location switching (`docs/location-customization.md`)
   depends on the client resolving the beacon UUID → `locationId` *without* the
   server, so the registration data model **must** be able to link the beacon
   UUID to a `locationId` (and optionally the mesh PeerID / a future announce
   TLV per `NODETYPE_RELAY_ONLY_SPEC.md`). The *mechanism* is an open item in
   `location-customization.md` (§8 Q1); the *requirement* is locked here.

## 9. Out of scope (this iteration)

- Any code changes (this document is the review artifact).
- Provisioning automation / self-registration.
- Beacon ranging or RSSI calibration (no TX-power field in the generic
  format).
- User toggle for beacon mode; QR-code display.

## Files to be modified at implementation time

- `app/src/main/java/com/blemesh/router/mesh/BleMeshService.kt:566-584` —
  add the scan response to `startAdvertising` (the call is at `:579`).
- `app/src/main/java/com/blemesh/router/router/LocalIdentity.kt` — extend
  (or add sibling `BeaconIdentity.kt`) to generate/persist `beacon_uuid` in
  the `"router"` prefs file.
- `app/src/main/java/com/blemesh/router/ui/ConfigActivity.kt` — display
  UUID + Copy button.
