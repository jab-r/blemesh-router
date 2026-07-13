> **Mirror.** Canonical source: `loxation-sw/docs/SUBLOCATION_ADVERTISEMENT.md`
> (authored there on branch `spec/sublocation-advertisement`). The cross-repo
> lockstep artifact is the **fixture** `fixtures/router_stage_advertisement.json`,
> checked in byte-identically across all three repos (loxation-sw,
> loxation-android, blemesh-router); this doc is a convenience mirror of the spec
> text, carried in loxation-sw and here. Relative paths in the text below are
> loxation-sw-relative. In THIS repo the emitter lives in
> `app/src/main/java/com/blemesh/router/router/BeaconIdentity.kt` and
> `mesh/BleMeshService.kt` (`startAdvertising`).

# Sublocation Advertisement — Router-Programmed Stage Binding

**Status: SPEC — approved design, no code has landed.** July 2026.
Companion docs: `docs/SUBLOCATION_CUSTOMIZATION_SPEC.md` (Part II — the sublocation
model this extends; its §6 pre-registration contract is amended by this doc),
`../blemesh-router/docs/beacon-mode.md` (the advertisement this doc versions),
`fixtures/router_stage_advertisement.json` (lockstep vectors, all three repos).

**Problem.** The pack manifest's `beacons` map is deliberately the single client-side
source of beacon→stage binding (SUBLOCATION_CUSTOMIZATION_SPEC §6, Jul 2026: the
identify-learned client store was removed). Consequence: a mid-event router swap needs
a pack re-upload to fix the stage binding, even though the spare is already pre-listed
as a `""` membership entry and venue keep-alive covers it.

**Design.** The blemesh-router gains an operator-configurable `sublocationId` (stage
id) in its settings UI, carried in the beacon **advertisement** it already emits — a
4-byte stage hash next to the `beacon_uuid` in the scan-response manufacturer data.
Phones honor it as a **live, ephemeral overlay** on the manifest's static binding.
A swapped-in router self-describes its stage; no pack re-upload for updated clients.

**Trust model — unchanged where it matters.** The manifest remains authoritative for
venue **MEMBERSHIP** (`memberBeaconIds` — keep-alive, L2 re-promotion) and stage
**VALIDITY** (declared `sublocations`). Only the binding **EDGE** becomes dynamic, and
it is recomputed per scan flush from fresh sightings — **nothing is persisted
client-side**, so the "no client-side beacon learning" decision (PR #89) holds: the
overlay's lifetime is the sighting's lifetime.

---

## 1. Two-tier binding model

- **Tier 1 (static):** the manifest `beacons` map, exactly as today. All anchor
  hardware. The **only** path for Holy-IoT iBeacons and Eddystone (their frames cannot
  carry the hash — their parse branches produce no hash, so tier-1 rules apply to them
  verbatim).
- **Tier 2 (advertised overlay):** blemesh-routers only. A validated advertisement
  overrides that beacon's tier-1 binding while the sighting is fresh.

An advertisement that is anything less than **unambiguously valid** is treated as
**ABSENT** (tier-1 binding / membership-only applies) — **never** as a demotion. This
is deliberately asymmetric with the manifest parse rule (an undeclared *static* binding
demotes to membership-only): a router typo must not zero out a correct authored
binding, whereas a manifest typo has no better fallback.

A genuinely new authoring mode falls out: a venue may declare stages with **zero**
static bindings and bind every router on-site at deploy time (fixture vector
`declared-stage-with-no-static-binding-binds`).

## 2. Wire format (normative — router emitter)

Scan-response manufacturer data, company id `0xFFFF` (`BeaconIdentity.COMPANY_ID`,
`../blemesh-router/.../router/BeaconIdentity.kt:32`; built in
`mesh/BleMeshService.kt` `startAdvertising`). Two views of the same bytes — Android's
`addManufacturerData(0xFFFF, payload)` takes the payload only; CoreBluetooth surfaces
the company id prepended little-endian (`0xFFFF` is byte-order-symmetric):

| Layout | Android payload | iOS-visible md | On-air AD element |
|---|---|---|---|
| **Legacy** (no stage configured) | `[beacon_uuid:16]` = 16 B | `[FF FF][beacon_uuid:16]` = 18 B | 20 / 31 B (11 spare) |
| **v1** (stage configured) | `[ver=0x01][stageHash:4][beacon_uuid:16]` = 21 B | `[FF FF][ver=0x01][stageHash:4][beacon_uuid:16]` = 23 B | 25 / 31 B (6 spare) |

Rules (MUST-level):

- **`beacon_uuid` is the TRAILING 16 bytes in every version, forever.** This is the
  invariant that keeps every deployed client's membership extraction eternally correct
  — both phone platforms derive the beacon id as the last 16 bytes of non-Apple mfr
  data (iOS `extractGlobalBeaconId`, `BeaconScanManager.swift:1492-1507`, takes
  `suffix(16)`). Any future layout revision extends BETWEEN the version byte and the
  UUID, never after it.
- Version `0x00` is **reserved-invalid** and never emitted. `0x01` = this spec.
- The router emits the **legacy** layout whenever no stage is configured — an
  unconfigured router is bit-identical on air to today's fleet.
- Byte budget is recorded above so future creep stays bounded: 6 spare bytes in the
  v1 scan-response element. (There is no TX-power field; that is unchanged.)

## 3. Stage hash (normative — all three platforms)

```
stageHash = SHA256(UTF-8(stageId))[0..3]     // first 4 bytes of the digest
```

- Compared **bytewise**, never as an integer — there is no endianness question.
- `stageId` is the exact declared sublocation id string. Ids are already constrained
  to lowercase ASCII `[a-z0-9-]{1,64}` (`SublocationPolicy.isValidSublocationId`,
  `loxation/Services/SublocationCustomization.swift:27-32`), so UTF-8 == ASCII and
  there is no Unicode-normalization surface. No trailing NUL/newline.
- The router validates (trim → lowercase → regex) **before** hashing and refuses to
  save invalid input — it never hashes un-normalized text, so the id space is
  identical on both ends.
- Worked vectors (pinned in the fixture): `mainstage → 70c29956`,
  `bassriver → b020d199`, `main-stage → 94b575e9`, and the pre-brute-forced collision
  pair `collide-7912` / `collide-c21f → 6a35cc01` (4-byte prefixes collide in seconds
  of search — the ambiguity rule in §5 is not hypothetical).

Why a hash and not the literal id: ids may be up to 64 chars; the scan response has
11 spare bytes. The manifest already ships the declared-id list, so the phone matches
the 4-byte prefix against hashes of declared ids — arbitrary-length ids fit in 4 bytes.

## 4. Router configuration & operator contract

- `ConfigActivity` (the router's only settings surface, alongside Wi-Fi SSID/PSK)
  gains a **stage id** text field: trim → lowercase → validate `[a-z0-9-]{1,64}`;
  empty **clears** (back to legacy layout). Persisted in SharedPreferences file
  `"router"`, key `sublocation_id` (mirroring `BeaconIdentity`/`WifiCredentials`).
- **Save → advertising restarts without reboot** (`stopAdvertising` /
  `startAdvertising` with the rebuilt scan response). Silent-until-reboot would gut
  the swap story; this is a hard requirement of the router PR.
- **N:1 is first-class: no uniqueness check anywhere.** Any number of routers may
  carry the same stage id — that is the normal way to cover a large stage. 1:N (one
  router, several stages) is impossible by construction (one config value). Downstream
  the selector already groups sightings per stage and takes the strongest RSSI
  (`SublocationSelector.evaluate`, `SublocationCustomization.swift:193-262`), so N
  routers advertising one stage compose with zero client logic.
- **Operator contract (replaces the pack-re-upload step for updated clients):** ALL
  venue routers **including spares** remain registered server-side AND listed in the
  manifest `beacons` map before the event (spares as `""` membership entries) —
  pre-registration is NOT relaxed; membership is manifest-only. Spares stay
  **unconfigured** (legacy layout) in the pool. At swap time the operator programs the
  stage id on the spare in ConfigActivity → updated phones re-bind live with zero data
  change. A pack re-upload remains: the remedy for **un-updated** phones, the only
  binding path for **iBeacon/Eddystone** anchors, and (together with registration)
  still required for **hardware acquired mid-event** (fails the membership gate).

## 5. Client honor policy (normative — iOS + Android loxation)

### 5.1 Parse (generic mfr-data branch)

Length gates **before** version:

| iOS-visible md | beaconId | stageHash |
|---|---|---|
| `count == 18` | `suffix(16)` | none (legacy) |
| `count == 23 && md[0..1] == FF FF && md[2] == 0x01` | `suffix(16)` | `md[3..6]` |
| `count == 23`, other version | `suffix(16)` | none |
| any other `count >= 16`, non-Apple | `suffix(16)` | none (today's behavior verbatim) |
| Apple prefix `4C 00` | none (never a stage sighting) | — |
| `count < 16` | whole-md hex string (today's behavior) | none |

The **hash parse is strict** (company + length + version) even though the UUID
extraction stays lax — a third-party 23-byte payload must not grow a garbage hash.
UUID extraction itself is **zero-diff**: `extractGlobalBeaconId` keeps returning
`suffix(16)` for every layout. Trap pinned in the fixture: a legacy 18-byte payload
whose `uuid[0] == 0x01` parses as legacy.

### 5.2 Sighting tap extension

`recordStageSighting` (`BeaconScanManager.swift:252-262`) records
`(rssi, seenAt, stageHash?)` — one record per beacon id, **last-writer-wins** (a live
reprogram simply overwrites at the next advert; no multi-hash state, no debounce
machinery — the selector's 8 dB / 2-flush hysteresis is the flap control).
`snapshotStageSightings` carries the hash through. Cap/retention/fresh-window
(256 / 180 s / 25 s) unchanged.

### 5.3 Effective mapping (per flush, no stored state)

Computed in `evaluateStageAndKeepAlive` (`BeaconScanManager.swift:280-333`) where
`stageMapping` is read today (`:316`):

```
effective = manifest.stageMapping                       // tier-1 statics (post-parse)
for s in freshSightings where s.stageHash != nil:
    guard s.beaconId ∈ manifest.memberBeaconIds          // A5: rogue/unregistered → ignored entirely
    guard s.stageHash matches EXACTLY ONE declared id    // A1/A2: 0 or ≥2 matches → advert ABSENT
    effective[s.beaconId] = matchedStageId               // A4: live binding wins over static
```

Precompute the manifest's `prefix4 → stageId` table once at manifest commit
(`LocationCustomizationService.commitIfCurrent`); mark colliding prefixes ambiguous
there. Evaluation cost: ≤ fresh-sighting count × one 4-byte table lookup.

### 5.4 Edge-case rules (pinned)

| # | Case | Rule |
|---|---|---|
| A1 | Hash matches ≥2 declared stages | Ambiguous ⇒ advert absent. Never guess (no lexicographic pick). Server-side pack-lint SHOULD warn on colliding declared prefixes (SUBLOCATION_CUSTOMIZATION_SPEC §14 OQ3). |
| A2 | Hash matches zero declared stages (stale config from another venue / typo) | Advert absent ⇒ static binding if any, else membership-only. Keep-alive unaffected (hash-blind). |
| A3 | Same beacon, two hashes inside the fresh window (live reprogram) | Last-writer-wins in the sighting record; visible switch governed by the existing selector hysteresis. |
| A4 | Static says A, advert says B | B wins while a fresh sighting carries it; when the advert disappears the overlay entry evaporates with the sighting and A resumes next flush. No hold-down timer. |
| A5 | Non-member beacon adverts a valid hash | Ignored entirely — no binding, no keep-alive, no re-promotion evidence. Membership gate evaluated FIRST, before any hash inspection. |
| A6 | Unknown version / unexpected length | §5.1 table. Length before version. UUID always extractable via trailing-16. |
| A7 | No manifest (venue without pack) | Feature inert by construction — both honor gates source from the manifest. The accepted "no manifest ⇒ no offline keep-alive" residual is untouched. Implementation note: the empty-mapping early-return guard (`BeaconScanManager.swift:317`) must test the **effective** mapping. |
| A8 | Keep-alive / `VerifiedVenueStore` / L2 re-promotion | **Hash-blind, pinned as invariant.** The stage hash MUST NOT enter the keep-alive predicate (`:301-310`), `refreshKeepAlive`, or `attemptOfflineVenueRepromotion` (`:346-363`) — membership only, both layouts, unchanged. |
| A9 | iBeacon / Eddystone anchors | Tier-1 only (§1). Their sightings carry `stageHash = nil` → tier-1 rules verbatim. |
| A10 | Hashing domain | §3 — validate-before-hash on the router; exact declared id string; bytewise compare. |
| A11 | Mixed fleet | §7 matrix. |
| A12 | Effective mapping changes between flushes | **Zero selector changes needed** (verified): an active stage vanishing from the mapping concedes the margin trivially (`strongest[active]` nil) → switch after 2 confirm flushes, or 120 s TTL fallback if nothing mapped is heard. `commitIfCurrent`'s drop-active-stage path and `setActiveSublocation`'s validation check the **declared** set only — and since the overlay never binds outside it, both remain sound untouched. |
| A13 | DoS / churn | No new surface: the generic branch already records any non-Apple ≥16 B mfr advert; the hash adds 4 bytes per existing entry, no new entries, no persistence. Residual named in §6. |

### 5.5 Invariants block (must not regress)

1. Advertised bindings **never expand the declared stage set** — all downstream
   validity checks (`setActiveSublocation`, palette binding, rule events) stay
   manifest-based and unchanged.
2. Membership is **manifest-only**; keep-alive, `VerifiedVenueStore`, and L2
   re-promotion are hash-blind (A8).
3. **Nothing persisted**: the overlay is recomputed per flush from the fresh-sighting
   snapshot. No UserDefaults, no learned store — the PR #89 decision stands.
4. Anything less than unambiguously valid ⇒ advert **absent**, never a demotion.

## 6. Security posture

The advertisement is **unsigned by design**: 6 spare bytes leave no room for a
signature, legacy clients couldn't verify one, and the router's Ed25519-signed mesh
announce is a different layer reaching a different code path (PeerID-keyed, never
correlated with the beacon path). All trust decisions are client-side gates
(membership + declared-stage validity).

**Named residual (widened, accepted):** the beacon layer was already unauthenticated —
membership and static binding were always replayable by copying a registered UUID. The
hash additionally lets a UUID-spoofer **relocate that beacon's binding among the
declared stages of a venue the user is verifiedly at**. Blast radius:
presentation-only styling (sublocation never enters locationId/claims/MLS/wire —
SUBLOCATION_CUSTOMIZATION_SPEC §4), flap-bounded by the selector hysteresis.
Consistent with the existing posture; recorded here so a later security review treats
it as examined, not missed.

## 7. Compatibility & rollout

### 7.1 Mixed-fleet matrix

| Phone | Router | Behavior |
|---|---|---|
| old | old / new-unconfigured | Bit-identical to today (legacy layout). |
| old | new, stage configured | `suffix(16)` still yields the UUID → membership, keep-alive, re-promotion all correct; stage binding = manifest static only (stale until pack re-upload). |
| new | old / new-unconfigured | Legacy parse, no hash → tier-1 exactly as today. |
| new | new, stage configured | Full tier-2: live binding, swap without re-upload. |
| any | iBeacon / Eddystone anchor | Tier-1 only, unchanged. |
| new | mid-event router config change | Old binding evaporates with the last fresh sighting (≤25 s), new binding subject to selector hysteresis. |

### 7.2 Pre-ship gate (blocking)

**Verify loxation-android's generic scanner extracts the TRAILING 16 bytes** of
non-Apple mfr data (the iOS `suffix(16)` equivalent). `BeaconIdentity.kt:15-20`
*claims* both scanners normalize this way, but it was not verified in
loxation-android source during this design. If Android extracts a prefix or fixed
offset, the 23-byte layout breaks old-Android membership/keep-alive — the one claim
with fleet-wide blast radius. This gate blocks the router PR.

### 7.3 Ship order (each step safe against all deployed counterparts)

1. **This spec + fixture** into all three repos (no behavior).
2. **Android suffix-16 verification gate** (§7.2).
3. **Router PR** (`../blemesh-router`): ConfigActivity field + prefs + v1 emission +
   restart-on-save; fix `docs/beacon-mode.md` staleness (status line, size table
   gains the v1 row); pointer to this spec.
4. / 5. **iOS honor PR** (this repo) and **Android loxation mirror** — either order:
   honor logic is inert without v1 adverts, v1 adverts are harmless without honor
   logic. Router-first preferred: it soaks the trailing-16 compatibility invariant
   before any client behavior depends on it.

## 8. Test vectors & follow-up work items

**Fixture:** `fixtures/router_stage_advertisement.json` — four groups: `stageHash`
(G1) and `encode` (G2) normative for the router emitter; `parse` (G3) and
`effectiveMapping` (G4) normative for the phone clients. Checked into all three repos
(`fixtures/README.md` convention). Selector dynamics stay in per-platform unit tests
seeded from G4 (selector behavior was never fixture-driven).

**iOS honor PR contents (follow-up, not this task):** strict hash parse in the
generic branch; sighting-tap `(rssi, seenAt, stageHash?)`; prefix table at manifest
commit; effective-mapping overlay in `evaluateStageAndKeepAlive` (+ the A7
effective-mapping guard fix at `:317`); replace the stale comment at
`BeaconScanManager.swift:315` ("A router swap mid-event is corrected by a pack
re-upload"); `SublocationCustomizationTests` additions consuming G1/G3/G4; negative
test: re-promotion succeeds identically with a hash-carrying sighting (A8).

**Router PR contents (follow-up):** §4 + §7.3(3).

**Android loxation mirror (follow-up):** G1/G3/G4 honor logic per
`loxation-android` conventions (note in `docs/FIXES_TO_BEACON_MANIFEST.md` ledger).
