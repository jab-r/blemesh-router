# Location Customization: Online → Offline, with the Router as Offline Anchor

**Status: DESIGN — for team review. No code has landed.**
June 2026. Companion: `docs/beacon-mode.md`, `NODETYPE_RELAY_ONLY_SPEC.md`.

## 1. Goal & motivation

Loxation supports **location customization**: when a client enters a venue, the app swaps in
venue-specific UI — styles (colors, typography), navigation (bottom tabs), profile/forms, and
behavioral rules — driven by a downloadable customization "pack" keyed by `locationId`.

Two problems block this from serving the BLE-mesh-router use case:

1. **Client parity gap.** iOS implements the full online flow (`LocationCustomizationService`).
   **Android does not** — it receives the `customization` field from the identify response and
   ignores it, and its own `LocationCustomizationService.kt` exists but is *never called*.
2. **It's online-only.** Resolving a sighting → `locationId` → customization requires a **server
   round-trip**. With no cell, a sighting is *dropped* (Android `BeaconScanCoordinator.kt:189-192`)
   or yields no location (iOS local mode). That defeats the router's entire reason for being —
   bridging mesh in **disconnected** environments.

This doc proposes a layered path to: (a) **client parity in online mode**, then (b) **offline
switching via client-side caching** — pre-cache packs + a beacon→location map while online, then
sight the router's beacon **offline**, resolve the location locally, and apply the cached pack
with **no cell and no mesh content delivery required**. The router's registerable beacon
(`beacon-mode.md`) becomes the **offline trigger** that makes the whole story work disconnected.

## 2. Current state (grounded)

### 2.1 iOS reference flow (loxation-sw) — the model to port

Trigger → fetch → bundle → apply → lifecycle:

| Stage | Where | What |
|---|---|---|
| **Trigger** | `BeaconScanManager.swift:773` → `LoxationApp.swift:590-618` | A location-type beacon sets `@Published currentLocationId`; an observer fires `refreshIfNeeded`, invalidates style caches, loads location rules, and emits a `location.entered` event. |
| **Fetch** | `LocationCustomizationService.swift:49-101` | `refreshIfNeeded(locationId:)` — checks `isCachedAndFresh` (`:144-152`, TTL `3600s` at `:17`), else gets a signed URL and downloads. |
| **API** | `LoxationApiClient.swift:1885-1900` | `GET /v1/location-config/customization` → `{ url, expiresIn }`. |
| **Download/extract** | `:159-168` (download), `:170-212` (extract) | Downloads the ZIP, extracts to `Application Support/Customization/{locationId}/` with path-traversal protection; writes `metadata.json` (`:214-226`). |
| **Apply (styles)** | `ChatStyleLoader` `ChatConversationStyle.swift:470-502`, `candidateDirectories:243-262`; `ProfileStyleLoader.loadProfile:265-293` | **Remote-first** resolution: the location's extracted `styles/` dir takes priority over the bundled assets. |
| **Apply (rules)** | `RuleService.loadLocationBundle:43-81`, `evaluate:99-147` → `AppDispatcher.dispatch:24-147` | Loads `assets/rules/*.rules` (CEL), evaluates on events, dispatches actions (sendMessage, navigate, selectTab, sendNotification, …). |
| **Lifecycle** | `customizationRoot:105-108`, `pruneOldCustomizations:130-140` | New location overwrites `activeLocationId`; pruning keeps **only the active** location; `activeLocationId` is **not persisted** across cold launch. Offline: falls back to existing cache if present, else bundled styles. |

**Pack contents** (extracted from the ZIP):

```
Customization/{locationId}/
├── metadata.json            # locationId, lastCheckedAt, lastDownloadedAt, size, etag
├── styles/
│   ├── *.style              # JSON — ProfileStyleFile: container/form/toolbars/fields/variants/presets
│   └── tabs/*.style         # e.g. chat.style: { variables, variants, presets }
└── assets/rules/*.rules     # JSON — RulesFile: rules[{event, condition(CEL), actions[{action,args}]}], fieldRules
```

### 2.2 Android gap (loxation-android)

The customization infrastructure is ~70% present but **the critical path is unwired**.

**HAVE:**
- Schema models — `DynamicStyleResolver.kt:22-151` (`StyleFile`, `FormSection`, `Toolbars`,
  `Toolbar`, `BottomTabs`, `SectionStyle`, `Field`, `VariantField`, `StyleVariant`). Same shape as
  iOS, Gson-deserializable.
- ZIP download + extract + TTL cache — `LocationCustomizationService.kt:39-121`
  (`fetchAssetsForLocation`, TTL at `:23`, zip-slip guard at `:83`). **Never called.**
- OkHttp HTTP stack (`LoxationApiClient.kt:47`); Compose theme (`Theme.kt:14-41`).

**MISSING:**
- API method `LoxationApiClient.getLocationCustomizationURL()` (no equivalent of iOS
  `fetchCustomizationURL`).
- `currentLocationId` tracking — `BeaconScanCoordinator` identifies beacons as a list
  (`handleIdentifyBeaconsSuccess:285-329`) but never selects/holds a current location, and **drops
  sightings entirely when offline** (`:189-192`).
- The **call** to the orphaned `fetchAssetsForLocation()`.
- **Remote-first** style loading — `DynamicStyleResolver` loads from **assets only**.
- Theme/nav **refresh** on location change.
- Consumption of the parsed-but-dropped `LocationIdentification.customization` field
  (`LoxationApiClient.kt:1117`).

> ⚠️ **Android cache durability:** the orphaned service extracts under `cacheDir`, which the OS may
> evict under storage pressure. For offline retention (L2) packs must live in `filesDir`, not
> `cacheDir`.

### 2.3 Server contract (loxation-server) — platform-agnostic, reusable as-is

| Endpoint | Where | Auth | Notes |
|---|---|---|---|
| `GET /v1/location-config/customization` | `location-config.ts:378-439` | Firebase token + `authWithLocationContext` | `locationId` comes from **session context, not a param**; returns `{ url, expiresIn }`; `404 not_found`, `400 missing_location_context`. |
| `PUT /v1/location-config/customization` | `location-config.ts:282-375` | Firebase token + `isDeveloper` claim | Multipart upload (`zipFile` + `locationId`), ≤20MB. |
| `GET /v1/identify/beacon/:beaconId` | `identify.ts:74-147` | Firebase token | `beacons/{id}.locationId` → `locations/{id}`; returns inline `customization` + `locationId`. |

- **Storage:** `location-customization/{locationId}/customization.zip` (Firebase Storage,
  `binaryStorageService.ts:904-906`); the GET returns a short-lived signed URL.
- **Pack/manifest schema:** `location-config.ts:84-124` and `manifest.json`
  (`locationCustomization.ts:288-324`) — colors, fonts, logo, `stylesheets[key].platform.ios|android`,
  `customApis`, `features`, `welcomeScreen`, etc. Stylesheet URLs can be **platform-specific**.
- **Auth is anonymous-capable** — the Android client already obtains tokens via Firebase
  `signInAnonymously()`. **No server change needed for L1.**

> **Two customization sources — don't conflate them.** The identify response carries an **inline**
> `customization` object (basic config). The downloadable **ZIP pack** (fetched separately) carries
> the full styles/rules/assets. iOS uses the ZIP as the rich source.

## 3. Architecture — layers

| Layer | Scope | Repos | Server change? |
|---|---|---|---|
| **L1 — Android online parity** | Make Android match iOS online: fetch + `currentLocationId` + apply. ~200–300 LOC. | loxation-android | No |
| **L2 — Offline cache switching** | Cache beacon→location map; durable pack retention (prefer-stale-when-offline); offline resolution path. | loxation-android, loxation-sw | No |
| **L3 — Venue manifest (optional)** | One-shot pre-cache of a whole venue: fetch-by-`locationId` / list `{beacon→location, packUrl, version}`. | loxation-server (+ both clients) | Yes |
| **L4 — Router/mesh delivery (later)** | Router serves packs over the existing bridgeable `LOXATION_QUERY/CHUNK/COMPLETE (0x41/0x42/0x43)` for **never-online** clients. | blemesh-router, loxation-android | No (reuses types) |

L1 is the foundation; each later layer is independently shippable.

## 4. Offline-cache mechanism (the L2 unlock)

**The flow we want:**

1. **Online, once:** as the client visits locations (or via an L3 bootstrap), it caches each
   location's **pack** *and* records the **beacon-UUID → locationId** mapping.
2. **Offline, later:** the client sights the router's beacon → looks up `locationId` in the local
   map → loads the **retained** pack for that `locationId` → applies it. No cell. No server. No
   mesh content delivery.

**Two missing pieces** make this work (the pack-caching itself already exists on both clients):

- **Offline beacon→location resolution.** Today the UUID→`locationId` mapping is *only* the server
  identify call. Cache that mapping locally (accumulated from prior online identifies, or from an
  L3 venue manifest) and resolve sightings against it when offline — instead of dropping them.
- **Durable retention.** Today's 1-hour TTL is a *freshness check*, and iOS
  `pruneOldCustomizations()` keeps only the *active* location. For multi-location offline
  switching, retain **all of a venue's packs** and prefer-stale-when-offline. (On Android, move
  storage off `cacheDir`.)

**Pre-cache caveat (drives L3):** `GET /v1/location-config/customization` derives `locationId`
from **session context**, so a client can only fetch the pack for where it currently *is*.
Pre-caching a whole venue before going offline therefore needs either (a) physically visiting each
location while online, or (b) an L3 venue-manifest/fetch-by-`locationId` capability.

**Cache-key design:** `beaconUUID → locationId → { packVersion, packPath }`. Carry a `version`/etag
so that, when back online, a changed pack invalidates the cached copy.

## 5. Router-as-offline-anchor synthesis

**Beacon mode is offline-viable, not online-only** — "online-only" describes today's *unfinished*
state, not the design. The router's beacon UUID is just an advertising-layer breadcrumb, but
combined with L2 it becomes the **offline location trigger**: once a client has cached the pack
(the code already half-does this) and the beacon→location map, sighting the router *disconnected*
switches venue UI with **no connectivity**. The "jiggling" is exactly L2's two pieces — cache +
resolve the beacon→location map offline, and retain packs durably — plus getting the map/pack onto
the device in the first place (online once, or an L3 bootstrap; a truly never-online client still
needs L4). The supporting points:

- Routers are **fixed infrastructure** (also the premise of `NODETYPE_RELAY_ONLY_SPEC.md`'s
  `fixedRelay` nodeType). A registered router == a fixed venue anchor.
- For the cached map to resolve the router offline, the **beacon UUID must map to a `locationId`**
  server-side. This is exactly beacon-mode **Open Question 5** (beacon UUID ↔ mesh/location
  identity correlation): it graduates from "nice to have" to a **dependency** of offline switching.
- L4 (router serving packs over the mesh) is only needed for clients that were **never online** to
  pre-cache. For the common "online at least once, then offline" case, L2 alone suffices and the
  router needs no content-serving code — only its beacon.

## 6. Cross-repo work breakdown

- **loxation-android (most):** L1 (API method, `currentLocationId`, call `fetchAssetsForLocation`,
  remote-first loader, theme/nav refresh, consume the dropped `customization` field) + L2 (local
  map, durable retention off `cacheDir`, offline resolution).
- **loxation-sw:** L1 exists. Needs L2 (retain all venue packs vs prune-to-active; cache the
  beacon→location map; resolve in local mode).
- **loxation-server:** L3 only (venue manifest / fetch-by-`locationId`). L1/L2 need no change.
- **blemesh-router:** L4 (originate `LOXATION_CHUNK` packs) + beacon-mode (the offline trigger).

## 7. Decisions locked (from design review, June 2026)

- **Online-first sequencing** — build L1 (Android parity) before any offline work.
- **Client-side caching is the offline mechanism** — not mesh delivery, for the common case.
- **Router is the offline anchor** — its beacon UUID triggers offline location switching.
- **Beacon stays generic manufacturer-data format** — iBeacon buys no iOS background sighting
  without loxation-sw client changes (see `beacon-mode.md`).
- **Beacon UUID survives identity reset**; **company ID `0xFFFF` for the pilot** (see
  `beacon-mode.md`).

## 8. Open questions for the team

1. **How does the client learn beacon→`locationId` offline?** Server-side registration linking the
   beacon UUID to a `locationId` (beacon-mode Open Q5), accumulated from online identifies, or an
   L3 venue manifest?
2. **Pre-cache strategy** — lazy-accumulate (cache each location as visited online) vs an L3
   venue-bootstrap endpoint that caches the whole venue in one online sync?
3. **Cache retention bounds** — size cap, eviction policy, how many venues/locations to retain.
4. **Staleness threshold** — how stale may an offline pack be before we refuse it; version/etag
   invalidation on reconnect.
5. **Consolidate the two customization sources** (inline identify field vs ZIP pack) or keep both?
6. **Android rules scope** — port the full CEL rules engine (`RuleService` + CEL + `AppDispatcher`,
   a large surface) or ship **styles/nav/forms first** and defer rules?
7. **Is "never-online" (L4) a real requirement,** or is "online at least once" acceptable for the
   pilot (which would let us defer L4 entirely)?

## 9. Out of scope (this iteration)

- L4 mesh-delivery wire details (message framing, router content store, directed-query discovery).
- Provisioning automation; beacon ranging / RSSI calibration.
- Any code — this document is the review artifact.
