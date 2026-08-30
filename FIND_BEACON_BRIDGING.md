# Find Beacon Bridging — router side

**Status: IMPLEMENTED (2026-08-29).** Router-side answer to `FIND_MODE_SPEC.md` §6 (canonical
source: `loxation-sw/docs/FIND_MODE_SPEC.md`, PR jab-r/loxation-sw#222). Companions:
`REGION_LOCAL_ROUTING_SPEC.md` (the broadcast gate this amends), `BACKBONE_PATH_ROUTING_SPEC.md`
(multi-hop forwarding), `docs/adaptive-mpr-fix.md` (the gossip bucket machinery).

The router is a **pure carrier** for find mode. It holds no find key, mints no grant, and cannot
decrypt a beacon — every decision below is made on the packet header alone. Nothing in this
document requires the router to understand what a beacon says.

> **iOS/Android can ship phase 2 dark against this.** The router carries 0x4A across the backbone
> as soon as phones emit it; no further router change is needed for phases 2–5.

## What the spec asked for, and what landed

| §6 ask | Status |
|---|---|
| Allowlist 0x4A for backbone crossing | `MessageType.CROSSES_BACKBONE` |
| Rate-limited per sender (≥30 s) | `FindBeaconBridgeGate`, `FIND_BEACON_MIN_CROSS_INTERVAL_MS = 30_000` |
| Newest-per-sender coalescing at the bridge | `FindBeaconBridgeGate.offer`/`drain` — held, not dropped |
| Add 0x4A to `ROUTABLE_TYPES` | **already satisfied, no change** — see below |
| §5 gossip/DTN bucket | `GossipSyncManager.findBeacons`, horizon 900 s, capacity 300 |
| §5 new RSR bucket at END of fixed order | `SyncTypeFlags` bit 11, last bucket in `syncWindowCandidates` |

### `ROUTABLE_TYPES` — the name is stale

`AUDIT.md` §4.1 retired the `ROUTABLE_TYPES` **include-list** after it silently dropped
PROTOCOL_ACK, LEAVE, NOISE_IDENTITY_ANNOUNCE and the LOXATION query/chunk/complete trio between
reference peers. Bridging is now **default-allow** via an exclude-list (`MessageType.NON_BRIDGEABLE`
/ `isBridgeable`), so a new type is routable the moment it exists — 0x4A needed no edit. Pinned by
`FindBeaconPolicyTest.isBridgeable` so it cannot regress if the list ever flips back.

## 1. Type code and sync bit — claimed in lockstep

- **`FIND_BEACON = 0x4A`.** Re-verified free before assignment, as §2 required: this repo and
  `loxation-android` (`BitChatProtocol.kt` jumps 0x49 → 0x50) both had the code unused.
- **Sync bit 11**, the next free index in all three implementations (iOS `SyncTypeFlags.swift` and
  `loxation-android` both stop at 10). The bitfield is interop-locked — **never renumber**.
- **On the no-compress list.** The shipped beacon is 110 payload bytes (ciphertext + Ed25519
  signature, §2) — past `CompressionUtil`'s 100-byte threshold, so the size heuristic no longer
  keeps it raw. Both phones pin 0x4A on `noCompressTypes` (iOS `BinaryProtocol`, Android
  `BitChatProtocol.NO_COMPRESS_TYPES`); `MessageType.NO_COMPRESS` is the third copy of that
  lockstep set and `FindBeaconPolicyTest` pins it. (An earlier §2 draft counted the beacon at 48 B
  and left it off the list — a miscount, corrected upstream.)

## 2. The backbone exception

`REGION_LOCAL_ROUTING_SPEC.md` §2 rule 1 is "broadcasts never cross the backbone," with §6
reserving "an explicit allowlisted exception" for operator/emergency traffic. **Find beacons are
the first use of that hook.** Friend-finding that stops at the region boundary is the feature not
working — crossing venues *is* the product (§6's reach table).

It is affordable because it is bounded on both axes the public MESSAGE broadcast is not:

- **~70 wire bytes.** Fixed, small, never fragments, never compresses.
- **One crossing per sender per 30 s**, enforced at the bridge (§3 below).

Aggregate backbone cost is therefore `senders_beaconing / 30 s`, independent of each sender's own
cadence — which is the property region-local routing exists to protect.

## 3. The gate — coalesce, don't drop

`FindBeaconBridgeGate` (`router/FindBeaconBridgeGate.kt`), consulted in `routeBlePacketToBridge`
after the crossing allowlist, released on the DM sweep tick.

Find mode is the first crossing broadcast a sender emits on a **fast** cadence: 10–15 s moving,
~5 s while FIND_REQ-boosted (§3). Announces, the other crossing broadcasts, sit at ~30 s and need
every crossing for home-router learning — so they are deliberately **not** metered.

**Why hold rather than drop.** Dropping inside the cooldown satisfies "≥30 s" but wastes most of the
window: it forwards whichever beacon happens to arrive *after* the cooldown expires, which for a
stationary sender (45 s keep-current cadence) can be another 45 s stale by the time it is picked. So
a beacon arriving inside the window is held, newest timestamp winning, and released when the
cooldown expires. Same backbone cost; the far region gets the freshest fix this router saw.

Holding is safe because the gate is on the **backbone leg only** — the beacon has already been
flooded into the local BLE cell by the mesh relay and stored for local gossip *before* the gate is
consulted. A coalesced-away beacon is never lost; only its cross-venue copy is deferred, and its own
`fixAge` keeps that deferral honest at the receiver.

Details that matter:

- **Cooldowns run on the router's clock**, never on packet timestamps — a skewed sender cannot buy
  itself a larger backbone share. Packet timestamps only order two beacons from the *same* sender,
  where skew is common to both.
- **Monotonic hold**: a reordered or replayed older beacon never displaces a fresher held one,
  matching the receiver's fresher-only merge.
- **Release re-arms the window**, so a held beacon cannot buy a free extra crossing.
- **Origin-only.** A beacon already on the backbone is re-forwarded unthrottled: the origin's gate
  has already bounded the rate, and a second throttle per hop would drop beacons in multi-hop
  topologies. Loop-freedom stays with the visited path tag.
- **Idle slots are reaped** on the sweep, so the gate is bounded by senders currently beaconing, not
  by every sender ever seen. Reaping an expired-cooldown slot is semantically free.
- **Fragment classification is unified.** `crossingType()` now serves both the allowlist and the
  gate, so a fragment wrapper can never be an unmetered path onto the backbone for a metered type.
  (A compliant 48-byte beacon cannot fragment; this closes the crafted case.)

Effective release interval is 30–45 s because holds drain on the 15 s DM sweep tick. Overshoot is
harmless — the spec asks for ≥30 s, and `fixAge` renders the delay honestly.

## 4. Gossip / DTN store (§5)

New store, **keep-latest-per-sender** — not the FIFO stream buffer. A beacon is a state update, not
cargo: the newest fully supersedes the sender's previous position, so keeping older ones would spend
GCS ids re-advertising fixes the receiver's fresher-only merge is going to discard.

| Property | Value | Why |
|---|---|---|
| Horizon | 900 s | §5 |
| Capacity | 300 **senders** | bounded by grant density, not crowd size |
| Sync interval | 30 s | matches the gate; faster only re-diffs an unchanged window |
| Bucket position | **last** | §5, so existing bucket indices are untouched |
| Eviction | oldest-fix-first | a burst of one-off senders can't push out tracked peers |

**The 60 s rule is deliberately broken here.** Public 0x44 gets a 60 s horizon because carrying a
stale public position is anti-useful — it misleads a stranger with no way to judge it. A find beacon
inverts every term: only the granted audience can decrypt it, and it carries its own `fixAge`. A
10-minute-old friend position rendered with an honest age IS the product (it is what a DTN mule
carry across a festival delivers).

**Beacons survive their sender's departure** — the only position store that does. The departure the
stale-announce reaper just observed is the exact moment the position becomes valuable; "which way
did my people go" is the question. Purging would silently defeat the 900 s horizon for precisely the
peers a mule carry exists to cover — the same shape of bug as purging DTN text cargo at departure.

Also wired: `GOSSIP_STORED` (so a ttl=0 backfill is **stored and served on the phone's next
REQUEST_SYNC**, not pushed into BLE where reference phones drop it at their RSR flood-gate), and
`CROSSABLE_SYNC_TYPES` (so backbone anti-entropy backfills a router that joined late or dropped a
push, instead of waiting out each sender's next 30 s crossing).

**Not** store-and-forward eligible: SNF is a directed-only replay buffer and the beacon is always a
broadcast. The gossip store is its carry story.

**Not** retry-tracked: a FIND_REQ-boosted sender emits every ~5 s — six originations per 30 s window
on one `(sender, broadcast, type)` bucket, i.e. a permanent bogus RETRY-STORM warning per finding
peer. Same class of false positive the fixed-window fix addressed for the phones' 20 s Noise
keepalive.

## 5. Out of scope / open

- **SOS is not implementable yet.** §6 says "allowlist 0x4A (and SOS)", but §7 keeps SOS on existing
  transports with **no new wire type in v1** — there is no code to allowlist. When §9 open question 3
  resolves toward a mesh broadcast form, it joins `CROSSES_BACKBONE`; whether it also joins
  `BACKBONE_RATE_LIMITED` depends on its cadence (an emergency broadcast probably should not be
  coalesced — recommend allowlist without the gate, since SOS is rare and latency-critical).
- **§9 open question 3, rate limit: answered — 30 s**, matching the spec's floor exactly. Raising it
  costs cross-venue freshness; lowering it costs backbone headroom. Re-tune from soak (§8 phase 8),
  not from a desk.
- **Router-to-router Noise** remains future work: the backbone is unauthenticated TCP today, so a
  beacon's *ciphertext* is safe in transit but its `senderID` header is not confidential to a
  network observer. Unchanged by this work, noted because find mode makes it more interesting.
- **No UI.** The gate logs `BCAST-COALESCE` / `BCAST-COALESCED` per decision; no snapshot/UI surface
  was added.

## 6. Verification owed (hardware — no CI, per §8)

1. **Two-router crossing.** A beacons in region 1, B (granted) in region 2 → B's Find screen shows
   an arrow to A. Confirms allowlist + injection.
2. **Rate limit under boost.** A FIND_REQ-boosted (~5 s) sender must produce **one**
   `BLE→bridge BCAST` per 30 s in `router.log`, not six; local peers still see every beacon.
3. **Coalescing freshness.** The crossed beacon's `fixAge` at the far region should reflect the
   *newest* beacon in the window, not the first.
4. **DTN carry across departure.** A walks out of region 1 entirely; A's last beacon must still be
   served to a late-joining granted peer in region 1 for up to 900 s, with an honest age.
5. **Late-router backfill.** Start router C after A has been beaconing → C's store fills from
   backbone anti-entropy within a gossip round, without waiting for A's next crossing.
6. **Old-router interop.** A router without this build must not choke on 0x4A (default-allow
   bridging); a mixed pair should still carry beacons in the direction of the updated router.

Per the field-test constraint, all of the above are single-device log captures — plan them as such.
