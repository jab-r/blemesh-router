# Backbone Path Routing — visited-router tag for >3 routers (Tier 3)

**Status: IMPLEMENTED (2026-06-30, router-only).** Landed behind the `BACKBONE_PATH_TAG`
flag in `router/MeshRouterService.kt` (default on). Design below preserved for context; the
**Implementation notes** section records how the §8 open questions were resolved.
2026-06-30. Companions: `AUDIT.md` (§4.3), `REGION_LOCAL_ROUTING_SPEC.md` (Tier 2),
`NODETYPE_RELAY_ONLY_SPEC.md`. Parent roadmap: `loxation-sw/docs/festival-scale-mesh-scaling.md`
(Tier 3).

## Implementation notes (what landed)

Router-only; no BLE wire change, no client change (as designed).

- **Frame format.** New `transport/BackboneFrame.kt` wraps the WiFi frame body as
  `[marker:0xBB][fmtVersion:1][visitedCount:1][visited PeerIDs:8·count][packet bytes]`. The marker
  cannot collide with a plain frame (packet version byte is always `0x01`), so a frame is
  **self-describing** — a receiver strips the tag by inspecting the first byte, no negotiation
  needed to *decode*. `MAX_VISITED = 16` (open Q1).
- **Loop guard (§2).** `MeshRouterService.routeBridgePacketToBle` drops any bridged packet whose
  visited path already contains this router (drop-on-self) and any path at the length bound;
  `bridgeDeduplicator` stays as a backstop for BLE-origin dupes and untagged legacy hops.
- **Home-router learning (§3).** Learned from the path **origin** (`visited[0]`), authoritative
  under multi-hop forwarding (the previous "whoever bridged it" is only the last hop); falls back to
  the sending router for untagged frames. Targeted directed bridging + the DM retry queue were
  already present (Tier 2) and now key off the origin. Directed traffic stays **targeted**, never
  flooded; a home router that is known but not an adjacent transport peer (pure ring) still queues —
  next-hop directed routing is out of scope for the tag primitive (documented limitation).
- **Multi-hop forwarding (§5, open Q5).** Crossing **broadcasts** that arrived *tagged* are
  re-forwarded across the backbone (append self, split-horizon skips peers already on the path), so
  announces propagate over non-full-mesh / ring topologies; loop-free by drop-on-self + dedup.
  Untagged (legacy-origin) broadcasts are left region-terminal to avoid falsely re-stamping the
  origin. In a full mesh this adds bounded (dedup-caught) announce fan-out — the price of
  topology independence.
- **TTL decoupling (§4, open Q3).** Adopted: with the tag on, WiFi hops do **not** decrement TTL, so
  a packet crosses many routers without starving. The anti-masquerade clamp (MAX_TTL arrivals →
  MAX_TTL−1 on injection) and the local-deliver TTL spend are kept regardless.
- **Rollout / negotiation (§6, open Q4).** New `ROUTER_CAPS` (0x72, `NON_BRIDGEABLE`) is sent
  directed to each freshly-connected router peer advertising path-tag support. A router sends tagged
  frames only to peers that replied with support (`RouterTransport.setPeerBackboneTag`, tracked per
  transport); everyone else gets plain frames (dedup-bounded). A legacy router consumes `ROUTER_CAPS`
  via its addressed-to-router check and never mis-injects it. `BACKBONE_PATH_TAG = false` reproduces
  the prior behavior exactly (plain frames, one TTL spent per crossing, dedup-only, no CAPS).
- **Open Q2** (home-router table expiry/capacity): reuses the existing `HOME_ROUTER_TTL_MS` (90 s)
  peer→home-router map from Tier 2.

**Router-only. No BLE wire change, no client change.** The tag rides the **WiFi backbone frame**,
which is already router-private; the loxation clients (`loxation-sw`, `loxation-android`) are not
involved and need no update.

## 1. Problem

Region-local routing (Tier 2) keeps local traffic off the backbone, but the backbone *itself* still
can't scale past 3 routers:

- **Loop avoidance is dedup-only** — `bridgeDeduplicator` keyed `{senderId}-{wireTimestamp}-{type}`
  (`MeshRouterService.kt:69-71,235,381`), which `AUDIT.md §4.3` explicitly caps at **≤3 routers**.
- **Dedup is a backstop, not routing.** It prevents *infinite* loops (a re-seen packet is dropped)
  and TTL bounds hops (each router hop decrements, `MeshRouterService.kt:411`) — but neither prevents
  the redundant *fan-out* across a dense router mesh, and the dedup table (5000 / 5 min) can evict
  under load and re-open a loop.
- **Directed traffic fans out to every router.** A directed packet whose recipient isn't a connected
  router peer is broadcast to all transports (`MeshRouterService.kt:293-296`), each injecting into its
  cell — wasteful.
- **Cross-region reach is TTL-limited.** With many router hops a packet's TTL burns out before it
  reaches a far region.

We need >3 routers, so dedup-only must be replaced with a real loop-prevention + routing scheme.

## 2. Design — visited-router path tag on the WiFi backbone leg

**Where it lives.** The WiFi transport frame is today `[length:4 BE][BlemeshPacket bytes]`
(`transport/WifiBridgeTransport.kt:18`, encoded via the shared `BinaryProtocol`). Extend it to
`[length][backbone-header][BlemeshPacket bytes]`, where **backbone-header carries a visited-router
PeerID list** (the first entry = the origin router). Routers own both ends of this framing
(`WifiBridgeTransport` encode at `:88-98`, decode at `:338-345`; interface `RouterTransport`), so this
is **router-internal**: no BLE 14-byte header change, no client change, no 3-platform lockstep. The
backbone-header is stripped before `bleMeshService.injectPacketFromWifi(...)` (`MeshRouterService.kt:403`),
which still receives the plain `BlemeshPacket`.

**Append-on-forward.** When a router forwards a packet over the backbone, it appends its own PeerID
to the visited list.

**Drop-on-self (the loop rule).** A router drops any bridged packet whose visited list already
contains its own PeerID. **Loop-free by construction at any router count** — this replaces dedup as
the primary backbone loop guard (keep `bridgeDeduplicator` as a cheap backstop for BLE-origin dupes).

**Bound the list** (e.g. max 16 PeerIDs) to cap frame growth; drop a packet that would exceed it (a
path that long signals a topology problem, not normal delivery).

## 3. Home-router learning + targeted bridging (supersedes region-local §7)

- The **origin entry** (first PeerID in the visited list) is the router that first bridged the packet
  — i.e. the sender's **home router / region**. A router receiving a bridged packet records
  `senderPeerID → originRouterPeerID` in a TTL-expiring table.
- **Targeted bridging.** Replace the fanout-to-all-routers path (`MeshRouterService.kt:293-296`): for
  a directed packet to recipient R, if the table knows R's home router, `sendToPeer` to **that one
  router** instead of broadcasting to all transports. **Fanout fallback** when R's home is unknown or
  a targeted delivery can't be confirmed (R moved) — so a stale mapping never loses a message.
- **This obsoletes the client-declared home-router extension** in `REGION_LOCAL_ROUTING_SPEC.md §7`:
  the router learns `sender → home-router` from its own backbone traffic, so **no client change is
  needed** for either membership or targeting. §7 is marked superseded there.

## 4. TTL decoupling (decision for the team)

Today each backbone hop decrements TTL (`MeshRouterService.kt:411`), so cross-region reach dies after
a few router hops. With the visited tag as the loop guard, backbone hops no longer *need* TTL for
loop safety. **Decide:** stop decrementing TTL on the WiFi leg (so a packet can traverse many routers
to a far region without starving), keeping TTL purely for BLE-side hop bounding. Keep the
anti-masquerade clamp on bridge→BLE injection (`:396-398`, clamps to `MAX_TTL-1`) regardless — that's
about direct-connection detection on the receiving phone, independent of the loop guard.

## 5. Zone backbone routing (roadmap Tier-3 item)

"A packet for region-A's routers should not traverse every router" — **targeted bridging (§3)
achieves this** for directed traffic (it goes straight to the recipient's home router). Combined with
region-local routing (Tier 2: broadcasts never cross the backbone), the backbone carries only
targeted directed traffic along a loop-free path — no separate zone-routing layer required.

## 6. Rollout / mixed-fleet

- **Flag-gated**, like `NODETYPE_RELAY_ONLY_SPEC.md`. A router without the tag falls back to
  dedup-only (safe at low router counts). Enable fleet-wide before relying on >3-router loop-freedom.
- **Frame-format compatibility:** the WiFi frame changes shape (`[len][hdr][packet]` vs
  `[len][packet]`), so an old router must not misparse a tagged frame. Add a small **backbone-format
  marker/version** at the head of the frame and negotiate support at connect via the existing
  peer-identity handshake (`WifiBridgeTransport.kt:230,241`); an old router rejects/ignores tagged
  frames rather than mis-decoding. No interop break at intermediate states — dedup still bounds loops
  for any hop that isn't tag-aware.

## 7. Client impact: NONE

Entirely router-side. The visited-router tag never touches the BLE wire or the clients;
`loxation-sw` / `loxation-android` need no change.

## 8. Open questions for the team

1. Backbone-header format + **max visited-list length** (frame-growth bound).
2. `sender → home-router` table **expiry / capacity**.
3. **TTL-decouple on the backbone** (§4) — adopt, or keep decrementing?
4. **Rollout handshake** for the new WiFi frame format (§6) — negotiate at connect vs a format marker.
5. Fold the existing **multi-hop forward** (`MeshRouterService.kt:406-419`) into the new scheme:
   replace its dedup-guarded forward with visited-tag-guarded + targeted forwarding.

## 9. Verification (at implementation time)

- **Loop:** a ring of >3 routers — a packet does not circulate; it's dropped when it returns to a
  router already on its own visited list.
- **Targeted:** directed→R goes only to R's home router once learned; fanout fallback delivers before
  the mapping is learned or when it's stale.
- **Reach** (if TTL-decoupled): a packet crosses N (>3) routers to a far region without TTL
  starvation.
- **Table expiry:** a moved sender's mapping ages out and re-learns from the next bridged packet.
- **Mixed-fleet:** tag-aware and legacy routers interoperate — no loops (dedup backstop) and no
  frame misparse.

## 10. Out of scope

- Any code (this document is the review artifact).
- BLE-side changes (none needed).
- The Tier-4 router internet-uplink gateway.
