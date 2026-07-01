# Region-Local Routing — keep local traffic off the WiFi backbone

**Status: DESIGN — for team review. No code has landed.**
2026-06-30. Companions: `AUDIT.md` (§4.3 backbone loop avoidance), `NODETYPE_RELAY_ONLY_SPEC.md`,
`docs/beacon-mode.md`. Parent roadmap: `loxation-sw/docs/festival-scale-mesh-scaling.md` (Tier 2).

The core spec (§1-§6) is **router-only** — the loxation clients (`loxation-sw`, `loxation-android`)
require **no change** (no wire change; the deferred item in §6 cannot ride a header bit anyway). §7 is
an **optional extension** that adds a small client-side payload change in exchange for explicit region
membership + targeted backbone routing.

## 1. Problem

At festival scale the structural ceiling is the WiFi backbone, and today the router bridges **too
much** onto it:

- **Every broadcast is bridged to every router.** `routeBlePacketToBridge` fans each broadcast out
  across all transports (`MeshRouterService.kt:279-284`). A public message / announce / location
  update originated in one region is replayed into every other region's cell. Aggregate backbone
  load scales with the whole crowd, not with cross-region need.
- **The local-delivery short-circuit is too narrow.** The router already declines to bridge a
  *directed* packet when both endpoints are local (`:241-262`) — but "local" means
  `isLocalPeer(recipient)` = **directly GATT-connected only** (`mesh/BleMeshService.kt:401-403`,
  backed by `peerIdToAddress`). A recipient in the **same BLE region** but not directly connected to
  the router falls through and gets bridged to every other router — where it also isn't, so it was
  pure backbone waste.

The backbone's loop avoidance is dedup-only (`bridgeDeduplicator`, `MeshRouterService.kt:69-71`),
which `AUDIT.md §4.3` caps at ≤3 routers. Cutting local traffic off the backbone is a prerequisite
for going wider, independent of the Tier-3 loop-prevention work.

## 2. Design (three rules)

1. **Broadcasts never cross the backbone.** They stay region-local, TTL-bounded, carried by BLE mesh
   only. Cross-region broadcast delivery does not happen — this is intended at scale.
2. **A directed message is bridged only if its recipient is not in this router's BLE region.** If the
   recipient is reachable through the local mesh, it is delivered locally and nothing crosses the
   backbone.
3. **"Region" = the whole local BLE mesh**, not just directly-connected peers — it includes devices
   several BLE hops away that reach this router via phone relays.

There is **no new identifier and no zone concept**: a router is identified by its existing beacon
UUID / PeerID (deviceId-like), and region membership is just a set of PeerIDs/deviceIds. The earlier
"cleartext zone id in the header" idea is explicitly dropped.

## 3. What exists today (reuse, don't rebuild)

- **Directed local-deliver short-circuit** — `MeshRouterService.routeBlePacketToBridge:241-262`:
  when `isLocalPeer(recipient)` it writes to every live BLE leg (`sendPacketToAllLegs`,
  `BleMeshService.kt:438`), decrements TTL to avoid direct-connection masquerade, and `return`s
  without bridging. This is the exact behavior we want — scoped too narrowly.
- **BLE receive hook** — `blePacketListener.onPacketReceived(packet, fromAddress)`
  (`MeshRouterService.kt:218-221`): every packet arriving over BLE, i.e. the natural place to record
  region membership from `packet.senderId`.
- **Local re-injection** — `bleMeshService.injectPacketFromWifi(packet)` (`:403`) broadcasts a packet
  into the local BLE cell; the mechanism to deliver a locally-kept packet without bridging.
- **Loop avoidance** — `bridgeDeduplicator` (5-min window), sufficient for ≤3 routers.

## 4. Change 1 — stop bridging broadcasts

- In `routeBlePacketToBridge`, remove/gate the `if (bridged.isBroadcast)` branch (`:279-284`): do not
  put broadcasts on any transport.
- Defensive mirror in `routeBridgePacketToBle` (`:359`): drop any broadcast that still arrives over
  the bridge (older peer routers) rather than injecting it locally.

**Consequence — cross-segment broadcast backfill ceases (this is the point).** The code comment at
`:268-274` states the current invariant plainly: *"routers don't gossip-sync each other over Wi-Fi …
the bridge is the only cross-segment backfill path."* So today the broadcast bridge is what carries
announces, public messages, location updates, and ttl=0 gossip-RSR replays into remote cells.
Removing it makes all of those **region-local by design** — exactly rule 1.

**Gossip is not broken by this:**
- REQUEST_SYNC is sent **directed** to specific peers (`sync/GossipSyncManager.kt:241-273`,
  `recipientId = peerID.toLongBE()`), with an explicit *"Do NOT re-add a broadcast REQUEST_SYNC"*
  note (`:253-261`); ttl=0 REQUEST_SYNC is already not bridged (`MeshRouterService.kt:276`). Gossip
  control traffic is unaffected.
- Gossip only reconciles what a node has **seen**. Once broadcast data no longer crosses regions, a
  remote region's phones simply never learn cross-region broadcasts — which is the intended scope
  reduction, not a regression.

> **Team decision to record in this doc before implementing:** is any broadcast type intended to
> stay venue-wide (e.g. an operator/emergency announce)? If so, it needs an explicit allowlisted
> exception to rule 1; otherwise all broadcasts become strictly region-local. Default: no exception.

## 5. Change 2 — widen "local region" beyond directly-connected

**Membership set.** Add a region set to `BleMeshService`: every PeerID (and its resolved deviceId)
whose packets are seen **originating over the BLE side** — the `senderId` of packets in
`onPacketReceived`, plus relayed announces — with a **last-seen TTL** so departed devices age out.
Do **not** add senders of packets arriving over WiFi (`routeBridgePacketToBle`); those are remote by
definition. This set is a superset of `peerIdToAddress` (which stays the "directly connected" set for
targeted leg writes).

**Decision.** Extend the short-circuit at `:241`: bridge a directed packet only when the recipient is
**neither directly connected nor a region member**. A region-member recipient is delivered locally
and never bridged.

**Delivery mechanism — the real subtlety for us.** `sendPacketToAllLegs` only reaches
*directly-connected* legs, so it cannot deliver to a multi-hop region member. Local delivery to a
multi-hop recipient must **re-inject into the BLE mesh** (`injectPacketFromWifi`) and let phone
relays carry the last hops. **But** directed 1:1 messages are `NOISE_ENCRYPTED` (0x12), which
`BlemeshProtocol.shouldRelay` returns **false** for — phones do **not** relay 0x12
(`MeshRouterService.kt:245-247`). So:

- For **relayable** directed types, re-injection + phone relay delivers to a multi-hop region member.
- For **0x12**, re-injection reaches only *directly-connected* phones. A 0x12 recipient that is a
  region member but not directly connected cannot be reached by re-injection **or** by bridging (it
  isn't at another router either). The correct handling is **hold/store-and-forward until the
  recipient reconnects directly** — never bridge it (bridging is pure backbone waste for a local
  recipient). This edge case is the router team's to design; see Open Questions.

## 6. Change 3 — provenance / "came via a router" (DEFERRED)

The client does nothing with this (user's call); its only real use is **router loop-avoidance beyond
3 routers**. It **cannot** be a reserved header flag bit: all three platforms re-derive the Flags
byte at `encode()` from only the three presence bits and drop reserved bits, so a phone that relays a
bridged packet **strips** any such marker — it would survive only router↔router WiFi hops, not
phone-relayed BLE hops. A durable "routers-visited" tag is a genuine wire change and belongs to
**Tier 3** (`AUDIT.md §4.3`), not here. For ≤3 routers, `bridgeDeduplicator` remains the loop guard.

## 7. Extension — client-declared home router (optional; DOES touch the client)

This upgrades Change 2 from *inferred* region membership to *explicit*, and adds **targeted backbone
routing**. Unlike §4-§6 it needs a small client change, so it's an opt-in follow-on.

**Idea.** Each client stamps, in the packets it originates, the **PeerID of the `fixedRelay` it
treats as its home router** (the nearest / most-direct relay-only peer it hears). Any router that sees
such a packet records `client → home-router`. Then:
- **Membership (precise):** a directed message to R is local iff R's declared home == this router →
  deliver locally, don't bridge. Replaces the observational heuristic in §5.
- **Targeted bridging (the real win):** if R's home is another router, send the packet to **that one
  router** (`transport.sendToPeer`) instead of the fanout-to-all-routers fallback
  (`MeshRouterService.kt:293-296`). Cuts backbone load and eases the ≤3-router loop ceiling
  (`AUDIT §4.3`) — a lightweight down-payment on Tier-3 backbone routing.

**Carry the router's mesh PeerID, not its beacon UUID.** Routing acts on PeerID
(`transport.sendToPeer` / `connectedPeerIDs()`); the beacon UUID is a separate, uncorrelated identity
(`docs/beacon-mode.md` Decision 3 / Open Q5). The client already learns router PeerIDs from
`fixedRelay` announces.

**Where it rides — add, don't blindly replace `locationId`.** The venue-wide `locationId` in Loxation
payloads (announce / beaconContext / location-update) looks redundant, but it is NOT dead: the
receiver consumes it to derive per-location messaging scope (loxation-sw per-location-profiles). So
carry the home-router PeerID as an **additional** payload element, or verify `locationId` is truly
unused first. Either way this is a **payload-semantics** change (all 3 platforms agree on the field's
meaning) — NOT a header byte-layout change; far lighter than the dropped zone-header idea, but it is
a client change.

**Router discovery already exists — no new message type needed.** The client selects its home router
from signals it already has:
- The `fixedRelay` ANNOUNCE (nodeType TLV 0x08, `model/AnnouncementData.kt`) is the de-facto
  router-discovery message: it yields each router's **PeerID + relay-only status**, plus directness
  via `ttl == messageTTL` (direct vs relayed — the same signal the gossip bootstrap uses). Emission is
  gated by `EMIT_FIXED_RELAY_NODE_TYPE` / the `NODETYPE_RELAY_ONLY_SPEC.md` rollout.
- **Ranking, now:** prefer a `fixedRelay` heard directly (`ttl == messageTTL`); else fewest relay
  hops. No beacon correlation required.
- **Ranking by signal, later:** proximity is clean from the *beacon* (RSSI), but that's the
  uncorrelated beacon UUID. To rank by signal yet act on PeerID, have the router include its **beacon
  UUID in its announce** — resolving `beacon-mode.md` Open Q5 as a side effect, still no new message.
- Router↔router discovery (mDNS `LanPeerDiscovery`) already exists.

**Ambiguity — client hears 2-3 routers (handle per use):**
- *Membership:* pick the strongest / most-direct with hysteresis; a wrong guess only yields a
  suboptimal bridge decision, never a lost message.
- *Targeted bridging:* a stale/wrong claim would LOSE the message, so it needs a **fanout fallback**
  (target R's claimed router; if delivery can't be confirmed, fall back to today's fanout).
  Optionally carry a small SET of heard routers at boundaries.

## 8. Client impact

- **Core spec (§4-§6): NONE.** Broadcasts-not-bridged and region-local directed routing are
  transparent to clients (a client simply stops receiving *cross-region* broadcasts — intended). No
  wire change; reserved header bits were already ignored on all platforms.
- **Extension (§7): a small client change** — clients stamp their home-router PeerID into originated
  packets. A payload-semantics change agreed across all 3 platforms; still no header byte-layout
  change. Ship the core first; adopt §7 as a follow-on.

## 9. Open questions for the team

1. **Venue-wide broadcast exception?** Any broadcast type that must stay venue-wide (operator /
   emergency)? Default is none — all broadcasts region-local.
2. **0x12 to a not-directly-connected region member** (§5): store-and-forward until direct reconnect,
   or accept non-delivery until reconnect? Either way: do not bridge.
3. **Region-set TTL / capacity** — how long since last BLE-side sighting before a peer is considered
   to have left the region (and its directed traffic resumes bridging)? Size cap / eviction.
4. **Does removing broadcast bridging affect any current operational assumption** (dashboards,
   cross-cell presence, SnF backfill for reconnecting phones)? Enumerate before landing.
5. **Adopt the §7 extension now, or ship the router-only core first?** The core needs no client
   change; §7 buys explicit membership + targeted bridging at the cost of a 3-platform
   payload-semantics change.

## 10. Verification (at implementation time)

- **Unit:** directed→directly-connected = local-deliver (unchanged); directed→multi-hop region
  member = local re-inject, NOT bridged; directed→remote peer = bridged; **broadcast = never
  bridged** (both directions); region-set entries expire and directed traffic to an aged-out peer
  resumes bridging.
- **Multi-device soak:** capture WiFi-backbone packet count before/after — same-region A→B directed
  traffic and **all** broadcasts should disappear from the backbone; cross-region directed traffic
  is unchanged.
- **Extension (§7):** a message whose R-home == this router → local-deliver; == another router →
  targeted to that one router (not fanout); unknown/stale claim → fanout fallback still delivers.

## 11. Out of scope

- Any code (this document is the review artifact).
- Tier-3 backbone routing / routers-visited loop prevention (§6) — separate spec.
- The zone-scoped-flooding wire change — dropped; superseded by region-local routing here.
