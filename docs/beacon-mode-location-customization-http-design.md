# Plan: Add HTTP-over-WiFi delivery option to the location-customization doc

## Context

Continuation of the location-customization design thread. The user asked about the feasibility of
running an **HTTP server on the router** to deliver customization packs to client phones, and
proposed using the clients' existing online/offline state to **switch the customization endpoint**
(online → `api.loxation.com`; offline/local → the router's local HTTP server).

Feasibility (verified): the HTTP server itself is trivial (~100–200 LOC on existing socket/coroutine
infra), but three constraints sit around it — (1) **no phone-joinable AP today** (all transports are
router-to-router; would need `startLocalOnlyHotspot`), (2) **no content store / no internet uplink**
(packs must be provisioned), (3) **Android client config** (hardcoded base URL + cleartext globally
off). The online/offline switch elegantly answers *when* to redirect and (via the gateway IP) *how to
address* the router, but not reachability or provisioning. This is a real "router as local content
origin" architecture and the right transport for **bulk** packs (WiFi ≫ BLE).

## Already done (prior turns) — no further action

- `docs/location-customization.md` created (L1–L4, offline-cache mechanism, router-as-anchor).
- `docs/beacon-mode.md` — open questions locked, stale refs fixed, cross-linked.
- `beacon-mode-design-doc.md` — status header + ref fix.
- ProGuard — confirmed no change needed.

## This task — edit `docs/location-customization.md` only

Add the HTTP-over-WiFi delivery option, framed as a **transport variant of L4** (so: L4a = BLE mesh
`LOXATION_CHUNK`; **L4b = HTTP-over-WiFi local origin**). Concretely:

1. **§3 layering table** — split L4 into L4a (BLE mesh, slow, reuses existing phone↔router BLE link)
   and L4b (HTTP-over-WiFi, fast/bulk, needs a phone-joinable AP). One-line each.
2. **New subsection — "L4b: HTTP-over-WiFi local origin (online/offline endpoint switch)":**
   - **Endpoint switch** — clients pick the customization host by mode, reusing the existing SSOT
     `AppStateManager.isOnlineMode` (iOS `LoxationApp.swift:49` / `AppStateManager`; Android
     `AppStateManager.kt:48-63`), which already gates identify/location/privacy. **Caveat:** that flag
     is a *sticky latch* (set at registration, not regressed on connectivity loss —
     `BeaconScanCoordinator.kt:185-186`), so the real switch trigger should be *live* connectivity /
     "joined a no-uplink AP," not the latch alone.
   - **Same fetch shape** — router serves a tiny mirror of `GET /location-config/customization` →
     `{ url, expiresIn }` + the ZIP; both clients download from whatever URL the metadata endpoint
     returns (no host pinning — `LocationCustomizationService` download path), so only the *metadata
     host* needs redirecting.
   - **Reachability** — requires a phone-joinable AP (`WifiManager.startLocalOnlyHotspot()`), a new
     router mode; flag the STA+AP+P2P radio-concurrency limit; note the **gateway-IP = endpoint**
     discovery shortcut (no mDNS needed when the phone is on the router's AP).
   - **Provisioning** — router has no uplink/content store today; packs sideloaded, pushed from an
     uplinked router, or fetched by giving the router its own HTTP client + loxation auth.
   - **Client changes** — Android: configurable base URL (`LoxationApiClient.kt:34`) +
     `network-security-config` cleartext exception (`usesCleartextTraffic="false"` today, targetSdk
     35); iOS: config-only (`API_BASE_URL` in Info.plist + `NSAppTransportSecurity` local exception).
   - **When to prefer** — bulk/live content; complements L2 (cache, online-once) and L4a (BLE,
     never-online but slow).
3. **§8 open questions** — add: exact switch signal (sticky `isOnlineMode` vs live connectivity vs
   on-AP detection); how packs get provisioned onto an uplink-less router; STA+AP concurrency on
   target hardware.

### Files
- **Edit:** `docs/location-customization.md` (only).

## Verification

1. Render `docs/location-customization.md`; confirm the L4a/L4b split and the new subsection read
   coherently and don't contradict §4/§5.
2. Spot-check the new citations against live code (`AppStateManager.kt:48-63`,
   `BeaconScanCoordinator.kt:185-189`, `LoxationApiClient.kt:34`, `usesCleartextTraffic`,
   `LoxationApp.swift:49`) — all verified during exploration.
3. Doc-only — no build/test. Do **not** commit unless asked.
