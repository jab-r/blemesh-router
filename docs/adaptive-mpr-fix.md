# Adaptive MPR + DTN Gossip Tuning — Router Parity Notes

> **Source:** iOS shipped the festival-scale adaptive-MPR + DTN carry tuning in
> `loxation-sw` PR #80 (2026-07-01). Plan: `loxation-sw/docs/at-festival-scale-dynamic.md`.
> Reference implementation: `loxation-sw/loxation/Services/GossipSyncManager.swift` and the
> `MeshRelaySuppression` enum at the bottom of `loxation-sw/loxation/Services/BLEMeshService.swift`.
>
> Everything here is **receiver-local — NO wire change**. A node that adopts none of it stays
> protocol-correct (it just transmits more / reconciles worse). Rollout can be staggered.
> The router's ROLE is unchanged: fixed backbone hub, never suppresses, never duty-cycles.

## Why the router is affected at all

The router is the mesh's **DTN reconciliation point**: anything a phone hands it gets injected
into the WiFi backbone and re-radiated grounds-wide. Two iOS changes concentrate gossip load on
routers:

1. **Gradient-biased carrier selection.** Phones now deterministically spend **one of their two
   per-round sync slots on a connected `fixedRelay` hub** whenever one is reachable (previously
   the 2-of-N pick was uniformly random). Expect a much higher REQUEST_SYNC arrival rate per
   router in dense cells — plan capacity accordingly.
2. **DTN store growth on phones.** Phones now hold up to 6000 message packets / 2400 fragments
   at a 30-minute freshness horizon and hand them over on contact. The router is the most
   frequent contact.

## MUST adopt if the router raises its gossip buffers (or already has large stores)

These are **contract points**, not tuning suggestions. They were found by adversarial review of
the iOS implementation; skipping them makes a big-buffer node actively harmful.

### 1. RSR responses must be windowed to the GCS filter's coverage

The 400-byte GCS filter in a REQUEST_SYNC summarizes only **~355 ids** (`nMax =
estimateMaxElements(400B, p=7) = 3200/9`). A responder that diffs its **entire** store against
that filter re-sends every stored packet outside the requester's newest-~355 window — **every
round, forever** (the requester can never advertise the tail). With a 6000-packet store that is
a perpetual ~5,600-frame re-flood per round per requester, and an empty-filter initial sync
elicits the entire store over one BLE link — far past the 30 s RSR solicitation window, so the
tail is dropped at the requester's ttl==0 flood-gate *after* the airtime was burned.

**Rule:** the responder's candidate set for the diff must be computed by the **same function**
that builds the filter candidates: fresh packets, newest-first, truncated to the GCS id budget.
On iOS this is the shared `syncWindowCandidates(for:)` feeding both `buildGcsPayload` and
`_handleRequestSync`. Older cargo stays stored (for relay and DTN carry) — it is simply not
re-reconciled.

### 2. The window budget must be split per sync bucket (max-min fair)

A `.publicMessages` request covers announce + message + loxationAnnounce. Announces are ≤120 s
fresh (one per peer) — in a dense cell there are hundreds of them and they are **always newer**
than stored text. A single mixed newest-first window therefore crowds every message out of the
~355-id budget → **text cargo never syncs at exactly the density the plan targets**.

**Rule:** split the id budget across the requested buckets by **water-filling** (max-min fair):
every non-empty bucket gets an equal share of what remains; capacity a bucket can't use spills
to the others. Guarantees each bucket ≥ `budget/numBuckets`; a small presence bucket leaves
nearly everything to cargo. Fixed bucket order (must match across platforms for best
convergence): **announce → message → fragment → loxationAnnounce → locationUpdate**.

Worked examples (budget 355):
- counts `[20 announce, 5000 message, 15 loxation]` → `[20, 320, 15]`
- counts `[300, 6000, 200]` (dense cell) → `[119, 118, 118]` — cargo keeps a fair floor
- single bucket `[6000]` → `[355]`

Reference: `GossipSyncManager.bucketBudgets(counts:budget:)` (pure function + unit tests in
`loxation-sw/loxationTests/BLEMesh/MeshScalingPolicyTests.swift`).

### 3. Peer departure must NOT purge message/fragment cargo

The pre-DTN behavior deleted a departed sender's stored messages when their announce went stale
(or on LEAVE). That silently defeats the raised carry horizon — cargo, by definition, outlives
the originator's proximity. **Departure retires presence state only** (announcement, profile
packets, positions); message/fragment cargo expires via its own per-type horizon.

### 4. Announce freshness horizon strictly greater than the stale-peer timeout

iOS: announce horizon **120 s** vs stale timeout **60 s**, and the stale reaper runs **before**
generic expiry in periodic maintenance. At an equal horizon, generic expiry silently drops the
entry before the reaper (which owns the departure cleanup side effect) ever sees it — the
reaper becomes dead code.

## SHOULD adopt for parity (per-type horizons and buffers)

| Knob | Old | New (iOS) | Rationale |
|---|---|---|---|
| message max age | 900 s | **1800 s** | async text IS the DTN cargo; 900 s GC'd what carriers carried |
| fragment max age | 900 s | **1800 s** | fragments carry oversized text |
| locationUpdate max age | 900 s | **60 s** | real-time only; carrying stale positions is anti-useful |
| announce max age | 900 s | **120 s** | presence; must stay > stale timeout (see #4) |
| loxationAnnounce max age | 900 s | 900 s | profile, semi-static — unchanged |
| message store capacity | 1000 | **6000** | horizon must not be undone by FIFO eviction |
| fragment store capacity | 600 | **2400** | same |
| maxPeersPerSync | 2 | 2 (gradient-biased) | routers: N/A — router sync policy is its own |
| message/fragment sync interval | — | **never density-stretched** | the carry handoff must catch brief passing contacts |

## Explicitly NOT changing (pin these)

- **MPR suppression: routers stay EXEMPT.** `fixedRelay` nodes never suppress relays — they are
  the backbone. The adaptive k_eff / degree-biased jitter is a *phone* behavior; the router only
  benefits (fewer redundant copies arriving).
- **nodeType TLV 0x08 stays `0x00` mobile / `0x01` fixedRelay.** The proposed `0x02`
  relay-capable-mobile value is **DROPPED (decided)** — no consumer; do not implement.
- **No new wire message type.** DTN reuses the existing gossip/RSR types; ttl==0 remains the RSR
  marker; the RSR flood-gate keying (link peer, not embedded senderID) is unchanged.
- `routerPresentTTL = 2` client-side origination deferral near a hub — already correct, no
  router change.
- Region-local routing / backbone path routing specs (`REGION_LOCAL_ROUTING_SPEC.md`,
  `BACKBONE_PATH_ROUTING_SPEC.md`) are orthogonal and unaffected.

## Known residuals on iOS (so you don't chase ghosts)

- iOS receive dedup stays 300 s / 1000 ids (widening it would slow the shared announce-back
  cadence). The windowed responder removes the systematic re-delivery source; occasional
  re-delivery of >5-min-old cargo is possible and tolerated.
- iOS displays public messages only from originators announced within ~60 s; DTN-carried
  messages from long-departed originators are stored/relayed but not displayed. Display policy
  is a separate product decision — routers need no change.
