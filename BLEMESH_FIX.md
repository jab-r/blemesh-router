# BLE Mesh / Gossip Sync Fixes — blemesh-router

From the June 2026 cross-repo parity review (blemesh-router @ e0aee58,
loxation-android @ d16e7d70 + working tree, loxation-sw @ c0f790a7). Companion docs:
`../loxation-android/BLEMESH_FIX.md`, `../loxation-sw/BLEMESH_FIX.md`.

What was confirmed in lockstep (no action): header layout/flags, packet-ID derivation,
RequestSyncPacket TLV format, NO_COMPRESS list (matches Android exactly), relay policy
(ttl>1, never 0x12 — *since revised: 0x12 IS relayed as of 2a32c0c 2026-07-01, iOS parity*),
TTL decrement on every crossing with floor 0 + inbound MAX clamp,
mesh dedup keys, raw-DEFLATE emit + dual-format decode, fragment relay-without-
reassembly, and — important — **store-and-serve ttl=0 replays pass the phones'
link-peer flood gates by construction** (unicast replies inside a phone-registered sync
window only). The router's GCS filter is correct (dedup, original-M-after-trim, 0→1
remap) and is one of the two reference implementations for the Android fix.

---

## HIGH

### 1. Decompressor accepts truncated/partial inflate and propagates corruption

`protocol/CompressionUtil.kt:58-65` — on bound-exceed the loop `break`s and **returns
the partial buffer**; there is no `inflater.finished()` truncation check. And
`protocol/BinaryProtocol.kt:186-187` accepts any non-null result without comparing
`decompressed.size == originalSize`. Because the router **re-encodes and
relays/bridges** what it decodes (`mesh/BleMeshService.kt:1046,1070`,
`transport/WifiBridgeTransport.kt:89`) and inserts results into its gossip stores under
an ID computed over the corrupt payload (`sync/GossipSyncManager.kt:212-217`), a
truncated/corrupt compressed packet is laundered into a "valid" packet and served to
phones.

Fix (port from the references — both are strict):
- `CompressionUtil.decompress`: return null if `!inflater.finished()` after the loop;
  return null (don't break-and-return) on bound exceed; cap decompression at 1 MiB when
  no size hint. Reference: loxation-android working-tree `utils/CompressionUtil.kt`
  (landing with its next commit) and its `CompressionUtilTest`.
- `BinaryProtocol.decode`: drop the packet when `decompressed.size != originalSize`.
  Reference: iOS `BinaryProtocol.swift:408-411` (commit 91695d72 "drop on decode
  failure").

---

## MEDIUM

### 2. Add LOCATION_UPDATE (0x44) gossip sync support

Both apps added locationUpdate sync (Android d16e7d70, iOS c85121e1): SyncTypeFlags
bit 10, store capacity 100, 15s schedule, 4th initial-sync round, and 0x44 in the
flood-gated syncable set. The router has none of it:

- `sync/SyncTypeFlags.kt:12-24` stops at bit 9 — a phone's bit-10 REQUEST_SYNC decodes
  but matches nothing.
- `sync/GossipSyncManager.kt`: no `locationUpdatePackets` store, no capacity/interval in
  Config (`:31-43`), `onPublicPacketSeenInternal:224` ignores 0x44, no response branch
  in `handleRequestSyncInternal:288-328`, initial sync sends only 3 rounds (`:141-155`).
- `model/MessageType.kt:133` `GOSSIP_STORED` omits 0x44, so bridged ttl=0 0x44 replays
  fall through `injectPacketFromWifi` to a raw BLE broadcast
  (`mesh/BleMeshService.kt:474-503`) that far-segment phones drop at the gate unless a
  sync window to the router happens to be open. The e0aee58 commit message acknowledges
  this as a stopgap.

Effect today: cross-segment indoor-positioning **backfill** is mostly lost (live ttl≥1
0x44 broadcasts still bridge fine), and the broadcast-push fallback generates
`gossip.security` warnings on phones. Fix is mechanical: bit 10 (`LOCATION_UPDATE -> 10`
matching Android `SyncTypeFlags.kt:23` / iOS `SyncTypeFlags.swift:25`), a 100-cap store
with a 15s schedule, a response branch, 0x44 in `GOSSIP_STORED`, and a 4th initial-sync
round — constants identical to the apps.

### 3. Store-and-forward replays up to 60s-stale NOISE_HANDSHAKE frames

**RESOLVED (2026-07-16):** 0x10 and 0x12 are no longer in `SNF_ELIGIBLE`
(`model/MessageType.kt:136-141`, with the rationale below recorded in its doc comment).

`model/MessageType.kt:114-119` `SNF_ELIGIBLE` includes 0x10 NOISE_HANDSHAKE (and 0x12
NOISE_ENCRYPTED); buffered frames replay up to `SNF_MAX_AGE_MS = 60_000`
(`mesh/BleMeshService.kt:79`) on the peer's next direct ANNOUNCE. Both apps treat an
incoming handshake m1 on an established session as "peer restarted" and tear the live
session down (`resetSessionPreservingKeys` / `clearSessionPreservingKeys`) — so a
stale, never-delivered m1 replayed after the phones have re-established over another
path churns a healthy session. The apps' dedup only catches byte-identical retransmits.
Fix: drop 0x10 and 0x12 from `SNF_ELIGIBLE` (a replayed 0x12 is useless after any
session reset anyway), or give Noise types a ~10s SNF age.

---

## LOW

### 4. Remove the broadcast REQUEST_SYNC fallback

`sync/GossipSyncManager.kt:239-258` falls back to `sendRequestSyncBroadcast` when zero
peers are mapped. Both apps deleted this pattern with explicit do-not-re-add comments
(Android `GossipSyncManager.kt:315-321`, iOS `:282-288`). For the router it is nearly
dead code (fires only with zero announce-mapped peers, i.e. nobody to hear it) and it
invites phones lacking the router's address mapping to broadcast their ttl=0 RSR burst,
which other phones drop with `gossip.security` warnings. Delete it, or add the agreed
do-not-re-add comment if there's a reason to keep it.

### 5. Update stale AUDIT.md sections; resolve the dead `isValidResponse`

- §4.3 / §2-5.1 justify not gating RSRs as "parity ... pending a paired change in
  loxation-android" — that change has landed on both apps (Android
  `BLEMeshService.kt:765`, iOS `BLEMeshService.swift:2108`). Not gating is now a
  **deliberate divergence** (bridge backfill by design); update the rationale.
- `sync/RequestSyncManager.kt:25` `isValidResponse(from)` has zero call sites and its
  signature has drifted from the apps' `isValidResponse(from, isRSR)`. Delete it or
  align the signature so it can't be wired up with wrong semantics later.
- §2-4.4 "TTL preserved across bridge" describes pre-e1ea500 behavior — current code
  spends one TTL per crossing with floor 0 (`MeshRouterService.kt:267-277`) and clamps
  inbound MAX (`:396-398`).
- §3 bridgeDeduplicator numbers are stale (now 5 min / 5000, `MeshRouterService.kt:71-74`).

### 6. Fragment reassembly key omits the sender

Apps key in-flight assemblies as `"<senderHex>:<fragID>"` (Android
`MeshStateStore.kt:348`, iOS `BLEMeshService.swift:2832`); the router keys on
fragment-ID hex alone (`mesh/BleMeshFragmentationManager.kt`). Random 8-byte IDs make
accidental collisions negligible, but a malicious peer can poison another sender's
in-flight transfer at the router by reusing its fragID. Include the sender in the key.

### 7. Phones perpetually fire doomed Noise handshakes at the router (cross-team)

**RESOLVED (2026-07-16):** the router now advertises `nodeType = FIXED_RELAY` (TLV 0x08)
in its ANNOUNCE (`mesh/BleMeshService.kt:66-72`, spec `NODETYPE_RELAY_ONLY_SPEC.md`), which
tells updated phones not to initiate handshakes toward it. Still true: isForMe 0x10s
terminate before `trackRetry`, so any residual hammering from old builds stays invisible
to the RETRY-STORM diagnostic.

The router announces a real noise static key every 30s (`mesh/BleMeshService.kt:356-379`)
but silently terminates 0x10 addressed to it (`:906-915`). Both apps proactively
initiate on announce when no session exists, so each phone retries a doomed handshake
indefinitely — airtime + log noise, invisible to the router's RETRY-STORM diagnostic
(isForMe packets terminate before `trackRetry`). Candidate fix: an agreed "relay-only"
flag in the router's AnnouncementData (needs app-side support; coordinate before
implementing).

---

## CROSS-TEAM DECISIONS (same text in all three docs)

**A. Unknown packet type policy — DECIDED (Jun 2026): default-allow (Android's
policy).** Unknown type bytes decode, dedup, and relay normally. Android
(`BinaryProtocol.kt:116`) and the router already comply — **no router action** (0x70/
0x71 remain router-local via NON_BRIDGEABLE and pre-injection consumption). iOS removes
its decode guard (`BinaryProtocol.swift:273`); see `../loxation-sw/BLEMESH_FIX.md`
item 1.

**B. Compression exclusion list — DECIDED (Jun 2026): iOS adopts the list.** iOS now
carries the identical 9-type sender-side exclusion (`BinaryProtocol.noCompressTypes`:
0x10, 0x12, 0x40, 0x41, 0x42, 0x43, 0x44, 0x48, 0x60), checked before its entropy
heuristic. All three platforms emit identical compression decisions per type;
protocol-control frames stay byte-stable on the wire. Decode remains flag-driven
everywhere. **No router action.** Landed in loxation-sw (`08a2ba64`).

**C. Timestamp normalization vs packet identity — DECIDED (Jun 2026): lockstep fix,
hash/relay the RAW wire timestamp.** Receiver-side normalization (µs/sec rescale,
epoch shift, clamp-to-now) is receiver-LOCAL, so feeding it into PacketIdUtil re-ID'd
any >1-year-skewed packet differently on every node (gossip could never reconcile it →
re-sent in every RSR burst until age-out), and re-encoding it on relay/bridge rewrote
the wire timestamp at every hop. The fix is receiver-local with NO wire change: the
decoded packet carries BOTH values — `timestamp` (normalized; freshness checks +
display ONLY) and `wireTimestamp` (raw 8 bytes as received; == `timestamp` for locally
built packets). `wireTimestamp` is mandatory for: (1) packet-ID derivation (GCS
membership), (2) receive dedup keys (the clamp branch yields a different value on every
receive, so replays of skewed packets passed dedup), (3) packet signature input (the
sender signs the raw value; verifying over the normalized one can never succeed on a
skewed packet), and (4) every re-encode — relay, RSR serving, bridge crossing
(byte-faithful; never rewrite the field). Fragmenting a relayed packet passes the
original's `wireTimestamp` through. Rollout-safe incrementally: normalization is the
identity for sane clocks, so behavior is unchanged for ~all real traffic; while
partially deployed, skewed packets disagree across versions — exactly today's failure
mode, no worse. **iOS is the reference implementation (landed):**
`BlemeshPacket.wireTimestamp`, `PacketIdUtil.computeId`, both dedup keys,
`signPacket`/`verifyPacketSignature`, `sendFragmentedPacket` — pinned by
`testSkewedWireTimestampPreservedIdStableAndRelayByteFaithful` in
`loxationTests/Protocol/BinaryProtocolTests.swift`. **Router action**: apply the same
contract wherever the router normalizes timestamps before computing GCS ids, deduping,
or re-encoding relayed/bridged/served frames. **Android action**: see
`../loxation-android/TIMESTAMP_LOCKSTEP_FIX.md`.
