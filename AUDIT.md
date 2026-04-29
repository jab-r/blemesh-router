# BLE Mesh Router — Correctness Audit

Audit target: `/Users/jon/Documents/GitHub/blemesh-router` (commit `bb3d385`).
References: `../loxation-android` (Kotlin) and `../loxation-sw` (Swift / iOS-macOS).
Audit date: 2026-04-21.

All findings below were spot-checked against the reference source files after delegated analysis; any claim the sub-auditor made that didn't survive verification has been corrected or removed.

Severity scale used:
- **P0** — breaks interop. Router will silently drop or corrupt traffic to/from reference clients on a common path.
- **P1** — degrades reliability or loses specific message classes. Router works for some traffic but not all.
- **P2** — correctness/robustness concern, not currently an interop break.
- **P3** — cosmetic / documentation / future-proofing.

---

## 1. Executive summary

Wire format for the 14-byte header, compression framing, padding, PacketIdUtil, GCS filter (bitstream, `h64`, `deriveP`, `m`), SyncTypeFlags bit layout, `RequestSyncPacket` TLV, announce-TLV, and relay rules are **byte-for-byte compatible** with both references. These are the things a newly-authored port most often gets wrong, and this one gets them right.

The router does **not** implement any new cryptography and correctly excludes `NOISE_ENCRYPTED (0x12)` from relay — end-to-end Noise sessions can transit through the router transparently.

However, there are three classes of defect that will cause observable failures when the router is dropped into a real mesh with loxation-android / loxation-sw peers:

1. **P0 — FRAGMENT wire format is wrong.** The router parses fragments using a 10-byte fragment header (`[id:8][index:1][total:1]`). Both references emit a **13-byte header** (`[id:8][index:2][total:2][originalType:1]`). Any fragmented packet from a reference client will reassemble to garbage, which then fails `BinaryProtocol.decode`.
2. **P0 — BLE GATT plumbing is incomplete.** No CCCD write, no `WRITE_TYPE_NO_RESPONSE`, no MTU-aware pre-send split. Each of these is individually sufficient to lose inbound or large outbound packets on real hardware.
3. **P1 — WiFi bridge drops several message classes that reference clients actively use.** `ROUTABLE_TYPES` is missing `LEAVE`, `NOISE_IDENTITY_ANNOUNCE`, and the loxation chunked-transfer types.

Everything else is either correct, cosmetic, or on-par with the reference.

---

## 2. Verified correct (parity)

The following were checked against the reference and are identical on the wire:

- **Header layout** (14 bytes, big-endian): `BinaryProtocol.kt:70-80` vs `loxation-android/.../BinaryProtocol.kt`.
- **Flag bits**: `FLAG_HAS_RECIPIENT=0x01`, `FLAG_HAS_SIGNATURE=0x02`, `FLAG_IS_COMPRESSED=0x04` — `BlemeshPacket.kt:27-29`.
- **BROADCAST_ADDRESS** = `0xFFFFFFFFFFFFFFFF` — `BlemeshPacket.kt:25`.
- **Compression framing**: 2-byte big-endian `originalSize` prefix, with a 4-byte fallback on decode that the iOS implementation also supports — `BinaryProtocol.kt:87-91, 186-196`.
- **NO_COMPRESS_TYPES** list matches the reference exactly, including the (intentional) fact that `NOISE_IDENTITY_ANNOUNCE (0x13)` is *not* in the list and therefore *is* compressed — `BinaryProtocol.kt:29-39` vs `loxation-android/.../BitChatProtocol.kt:172-181`.
- **MessagePadding**: block sizes `{256, 512, 1024, 2048, 4096}`, 1-byte vs 3-byte trailer split at 256 — `MessagePadding.kt:8`.
- **CompressionUtil**: ≥100 byte threshold, 0.9 unique-byte entropy test over first 256 bytes, zlib-wrapper first and raw DEFLATE fallback on decode — `CompressionUtil.kt:12-24, 68-70`.
- **PacketIdUtil**: SHA-256 over `type(1) || senderId(8 BE) || timestamp(8 BE) || payload`, truncated to 16 bytes — `PacketIdUtil.kt:14-27`. Matches both references exactly; gossip membership will agree cross-platform.
- **GCSFilter**: MSB-first bitstream, unary `q` + 0 + `p`-bit `r`, delta encoding with `(delta-1) >> p`, `h64` = first 8 bytes of SHA-256 masked to 63 bits, `m = n << p`, 10% trim loop — `GCSFilter.kt:45-125`. This is the single most fragile piece of the port; it is correct.
- **SyncTypeFlags**: bit indices 0–9 match `SyncTypeFlags.swift`; little-endian byte packing with trailing-zero trim — `SyncTypeFlags.kt:11-38, 83-95`.
- **RequestSyncPacket TLV**: tags `0x01..0x05`, M as uint32 BE, 1024-byte filter cap — `RequestSyncPacket.kt:21-95`.
- **AnnouncementData TLV**: encodes only tags `0x01/0x02/0x03`, decodes all seven — matches both references (`AnnouncementData.kt`).
- **PeerID derivation**: first 8 bytes of SHA-256(noise static pub key), big-endian Long round-trip — `PeerID.kt:25-53`.
- **MAX_TTL** = 7, relay rule `shouldRelay = type != NOISE_ENCRYPTED && ttl > 1`, TTL decremented *before* rebroadcast, jitter window 50–200 ms — `BlemeshProtocol.kt:23-31`, `BleMeshService.kt:452-458`.
- **Service / characteristic UUIDs** match `BLE_UUIDS.md`: `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5D` / `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E`.
- **Deduplication key** for non-fragments: `"{senderId}-{timestamp}-{type}"` — `BleMeshService.kt:514`. Same shape as `loxation-android`.
- **REQUEST_SYNC sent with `ttl=0`** so it is never relayed (sync is point-to-point) — `GossipSyncManager.kt:251, 269`.

---

## 3. P0 — Interop-breaking bugs

### 3.1 FRAGMENT wire format is 3 bytes too short

**Router parses:** `BleMeshService.kt:462-466`
```
fragmentId  = payload[0..8]          // 8 bytes
index       = payload[8] & 0xFF      // 1 byte
total       = payload[9] & 0xFF      // 1 byte
fragmentData = payload[10..]
```

**Reference emits/parses:** `loxation-android/.../service/BLEMeshService.kt:1957-1981, 2287, 2303, 2620, 2635` — the comment says it in so many words:
> `// Each FRAGMENT packet has a 13-byte header: [id:8][index:2][total:2][type:1]`

```kotlin
val index        = ((payload[8]  and 0xFF) shl 8) or (payload[9]  and 0xFF)
val total        = ((payload[10] and 0xFF) shl 8) or (payload[11] and 0xFF)
val originalType = payload[12]
val fragmentData = payload[13..]
```

**Impact.** Any fragmented packet from loxation-android or loxation-sw arriving at the router will be parsed with `total` equal to the high byte of the real index, `fragmentData` starting 3 bytes too early (including the real `total` low byte and the `originalType` byte), and reassembly will succeed with corrupt bytes which then fail `BinaryProtocol.decode`. Large messages (anything over the negotiated MTU) cannot traverse the router.

Interestingly, the router's own `buildDeduplicationKey` at `BleMeshService.kt:510-512` reads index as a **2-byte value** — so the deduplication key is correct, but the parse is wrong. The two code paths disagree with each other.

**Fix.**
```kotlin
// BleMeshService.kt handleFragment
val index        = ((packet.payload[8].toInt() and 0xFF) shl 8) or
                   (packet.payload[9].toInt() and 0xFF)
val total        = ((packet.payload[10].toInt() and 0xFF) shl 8) or
                   (packet.payload[11].toInt() and 0xFF)
// payload[12] is originalType — unused by reassembly, but advance offset
val fragmentData = packet.payload.copyOfRange(13, packet.payload.size)
```

Also update `BleMeshFragmentationManager.kt:12-14` — the header comment there already documents the wrong layout (`[index:1][total:1][originalType:1]`), which appears to be what misled the port.

Update the `handleFragment` guard `if (packet.payload.size < 10)` (`BleMeshService.kt:462`) to `< 13`.

---

### 3.2 No CCCD descriptor write — inbound notifications never arrive

**Router:** `BleMeshService.kt:387-390`
```kotlin
try {
    gatt.setCharacteristicNotification(char, true)
} catch (_: SecurityException) { }
```

`setCharacteristicNotification` only flips a local flag. The peer's GATT server does not start sending notifications until the client writes `ENABLE_NOTIFICATION_VALUE` to the standard CCCD descriptor (`00002902-...`). Without that, a reference client peripheral will accept the router's writes but the router will never receive the peer's responses via `onCharacteristicChanged`.

**Reference:** `loxation-android/.../service/BLEMeshService.kt:3428-3437` does exactly this:
```kotlin
val okNotify = gatt.setCharacteristicNotification(characteristic, true)
val ccc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
if (ccc != null) {
    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    gatt.writeDescriptor(ccc)
}
```

**Impact.** When the router acts as GATT **client** (it connects outward after scanning), it will write outbound fine but receive nothing back. This is asymmetric: traffic arriving at the router via its GATT **server** role (where the peer connects in and writes to the characteristic) still works, because that path does not require a CCCD write. In a two-router deployment where both advertise and both scan, roughly half of the BLE connections will be unidirectional.

---

### 3.3 Client writes don't set `WRITE_TYPE_NO_RESPONSE`

**Router:** `BleMeshService.kt:533-535`
```kotlin
char.value = data
gatt.writeCharacteristic(char)
```

Default write type is `WRITE_TYPE_DEFAULT`, which requires the peer to ACK each write before the next can be issued, serialized through the GATT queue. Mesh traffic is bursty and notification-shaped, not request-response.

**Reference:** `loxation-android/.../service/BLEMeshService.kt:3630`
```kotlin
characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
```

**Impact.** Under load the router will drop packets silently when `writeCharacteristic` returns `false` because a prior write is still in flight. The router's characteristic is already advertised with `PROPERTY_WRITE_NO_RESPONSE` (`BleMeshService.kt:287-290`) so peers on the other side will use NO_RESPONSE writes. The asymmetry means the router ingests fast but can only egress slowly.

**Fix.** Set `writeType = WRITE_TYPE_NO_RESPONSE` immediately after `onServicesDiscovered`, before the first write. Also track pending writes and refuse to enqueue when a synchronous write fails, so packets don't disappear quietly.

---

### 3.4 No MTU-aware splitting before send — large packets lost

**Router:** `BleMeshService.kt:146-164, 518-525` simply encodes the packet and writes it whole. `BleMeshFragmentationManager.split()` exists but has **no caller** (grepped).

**Reference:** `loxation-android/.../service/BLEMeshService.kt:2280-2311, 2620-2643, 3706` — every send site checks `serializedSize` against `maxSingleGattWrite` (typically `mtu - 3`) and calls `fragmentationManager.split(...)` when necessary, wrapping each piece in a `FRAGMENT (0x05)` packet with the 13-byte header.

**Impact.** Any packet the router originates or re-broadcasts that exceeds the negotiated ATT MTU minus 3 bytes will be truncated by the Android stack. A default-MTU (23) fallback would truncate at 20 bytes — smaller than a bare 14-byte header plus 8-byte senderId. Even at the requested MTU of 512, an `ANNOUNCE` with all optional fields, a fragmented `NOISE_HANDSHAKE`, or `LOXATION_CHUNK` payloads can exceed it.

In the router's normal role as a pure relay this might sometimes be masked: fragmented traffic arrives as pre-split `FRAGMENT` packets small enough to fit, and the router just rebroadcasts those fragments. But as soon as the router is the origin (e.g. its own `ANNOUNCE`, or gossip sync responses carrying many announces back-to-back), or the MTU differs between the two hops, this will drop traffic.

**Fix.** Plumb `connection.mtu` through `writeToConnection`, and when `encoded.size > connection.mtu - 3` wrap with `fragmentationManager.split(...)` + a `FRAGMENT (0x05)` BlemeshPacket per piece, exactly as the reference does.

---

## 4. P1 — WiFi bridge gaps

### 4.1 `ROUTABLE_TYPES` drops types the reference uses

`MeshRouterService.kt:47-59` whitelists:
```
ANNOUNCE, MESSAGE, FRAGMENT, DELIVERY_ACK, READ_RECEIPT,
NOISE_HANDSHAKE, NOISE_ENCRYPTED, LOXATION_ANNOUNCE,
LOCATION_UPDATE, MLS_MESSAGE, REQUEST_SYNC
```

The following types are **defined** in `MessageType.kt` but silently dropped at the bridge:

| Type | Hex | Consequence of not bridging |
| --- | --- | --- |
| `LEAVE` | `0x03` | Peer departures never propagate. Remote routers keep stale routing entries until the 60 s gossip timeout. Directed messages to a just-left peer go to the wrong side of the bridge. |
| `DELIVERY_STATUS_REQUEST` | `0x0B` | Delivery receipts for cross-bridge recipients break. |
| `NOISE_IDENTITY_ANNOUNCE` | `0x13` | Noise identity rotations don't propagate, breaking E2E sessions with remote peers after a key roll. |
| `LOXATION_QUERY` / `LOXATION_CHUNK` / `LOXATION_COMPLETE` | `0x41`–`0x43` | Loxation chunked transfers (the whole point of that subsystem) stop at the bridge. |
| `UWB_RANGING` | `0x45` | (local-only by design — this is fine to exclude) |

**Note on types not even defined in `MessageType.kt`:** `VERSION_HELLO (0x20)`, `VERSION_ACK (0x21)`, `PROTOCOL_ACK/NACK (0x22/0x23)`, `FAVORITED/UNFAVORITED (0x30/0x31)`, `WEBRTC_SDP/ICE (0x50/0x51)`, etc. are present in the references. `BinaryProtocol.decode` does **not** reject unknown types — it passes the raw `type: Byte` through. So packets with these types *can* still relay over BLE, but they fail the `typeInt in ROUTABLE_TYPES` check at `MeshRouterService.kt:157` and never cross the WiFi bridge. Whether those types need to cross the bridge in production depends on the deployment.

**Fix.** At minimum add `LEAVE`, `NOISE_IDENTITY_ANNOUNCE`, `LOXATION_QUERY`, `LOXATION_CHUNK`, `LOXATION_COMPLETE`. Consider replacing the whitelist with a blacklist that excludes only locally-scoped types.

---

### 4.2 Heartbeat monitor has no heartbeat

`WifiBridgeTransport.kt:241-256` tears down connections whose `lastActivity` hasn't moved in 45 s — but nothing ever writes a keepalive frame. `lastActivity` is only bumped on real traffic (`:206, :233`). On a quiet link or after a silent NAT timeout the TCP connection sits there, `isClosed == false`, `lastActivity` stale, waiting for the 45 s monitor to notice. Packets queued during that window go to a dead socket.

**Fix options (any one):**
- Enable TCP keep-alive at the socket level: `socket.keepAlive = true` in both `connectToRouter` and `handleIncomingConnection`.
- Introduce a bridge-only heartbeat (e.g. a zero-byte framed message, or a bare `REQUEST_SYNC` with empty filter) every 15 s.
- Piggy-back on the existing 15 s gossip `REQUEST_SYNC` broadcast from `GossipSyncManager`.

The first option is the cheapest and most standard.

---

### 4.3 Bridge has no loop tag — relies on dedup alone

`MeshRouterService.routeWifiPacketToBle` re-forwards every WiFi-received packet to all *other* connected routers (`:245-249`). In an A–B–C–A topology, the `bridgeDeduplicator` (`wifi2ble-{sender}-{ts}-{type}`) does catch the loop on the second arrival — so it terminates — but each packet is still duplicated `O(N)` times across the mesh before being killed. In a larger topology this becomes `O(N²)`. The dedup key is also shared across types and fragments, so a `FRAGMENT` packet whose dedup key is `{sender}-{ts}-FRAGMENT` will collide across distinct fragments that share the sender/ts/type tuple (they *don't*, because each fragment lives in a separate BlemeshPacket with its own timestamp, but this is a fragile invariant to rely on).

For the intended two-or-three-router deployments this is acceptable. If the bridge will grow, add a per-packet "routers visited" field (TLV-extended payload or a bridge-only envelope) and check it on ingress.

---

### 4.4 Bridged packets carry their BLE-decremented TTL

A packet that makes two BLE hops before being bridged arrives at the next router with TTL 5, and is then relayed over BLE again. The final reach is less than if the router had refreshed TTL at the WiFi boundary. The CLAUDE.md design calls the router "a bridge between mesh segments," so semantically refreshing TTL at the bridge boundary is reasonable, but it has to be a deliberate decision (and signaled somehow so the packet isn't infinitely amplifiable). Current behavior of preserving TTL is safe but limits reach; raising this to P1 because it determines how large a two-segment mesh can practically get.

---

## 5. P2 — Robustness / future-proofing

### 5.1 `RequestSyncManager.isValidResponse()` is never called

`RequestSyncManager.kt:25-29` defines it. `GossipSyncManager.handleRequestSyncInternal` (`GossipSyncManager.kt:277`) does not call it. **This is parity with `loxation-android`** — that reference also defines `isValidResponse` and never calls it in its `handleRequestSyncInternal` (verified at `loxation-android/.../GossipSyncManager.kt:303-333`). So this is not a regression, but it is a latent flaw in both codebases: any peer can flood unsolicited `REQUEST_SYNC` packets and force us to enumerate+filter the whole packet store per request. Worth fixing on both sides.

### 5.2 `handleFragment` dedup key asymmetry

`BleMeshService.kt:510-512` uses a 2-byte index for dedup, `:464` uses 1-byte for actual parsing. Once the P0 fix in 3.1 is applied, these align. Until then, dedup is keying on a value the parser never produces, which means fragment dedup is effectively broken (every arrival looks "new") and a fragment storm will hit reassembly directly.

### 5.3 Jitter source is not uniform

`BlemeshProtocol.jitterMs` uses `abs(System.nanoTime()) % range`, same as `loxation-android`. The bias over a 150 ms range is negligible for anti-collision purposes. Noted for completeness; not a bug.

### 5.4 `injectPacketFromWifi` uses a simple dedup key

`BleMeshService.kt:176` uses `"{sender}-{ts}-{type}"`. For non-fragment packets this happens to match the key built in `handleIncomingData` (line 514), so BLE ⇄ WiFi dedup is coherent. For `FRAGMENT`, the BLE path uses a *longer* key that includes `fragmentID-index` while `injectPacketFromWifi` uses the short form. A WiFi-received `FRAGMENT` will therefore dedup against any earlier `FRAGMENT` from the same sender/ts/type — causing legitimate fragments to be dropped after the first.

Once 3.1 is fixed, consider also using the fragment-aware dedup key in `injectPacketFromWifi`.

### 5.5 `MeshRouterService.remotePeerToRouter` learns from any sender

`MeshRouterService.kt:227-230` caches the sender PeerID → router for every incoming WiFi packet, with no evidence-weighting. If a packet from peer X traverses router A via router B (because B saw X first), then B forwards to C, C learns "X lives at B" — which is technically correct (that's the ingress side of the bridge for X) but if C later has a more-direct path to X via router D, it may not update. The cache has no TTL and only gets cleared on router disconnect (`:205-209`). In a stable topology this is fine; in a reconfigurable one, routing will stick to the first path seen until the routers reconnect.

### 5.6 Reconnect loop is unbounded

`WifiBridgeTransport.kt:114-118` retries forever with no backoff. If a configured router is permanently unreachable, we'll try every 5 s for the lifetime of the service. Add exponential backoff with a cap.

---

## 6. P3 — Cosmetic / documentation

- `BleMeshFragmentationManager.kt:12-14` header comment says `[fragmentId:8][index:1][total:1][originalType:1][data:var]` — wrong (see 3.1). The comment must be corrected when the parse is fixed.
- `RequestSyncManager` Kdoc (`RequestSyncManager.kt:7-11`) claims it "validates incoming RSR packets" — it doesn't (see 5.1). Either remove the claim or wire up the validation.
- `GossipSyncManager` comment "validates incoming RSR" (line 177 area) — same as above.
- `CLAUDE.md` protocol summary says fragments are `[fragmentID:8][index:1][total:1]` — needs updating to the real 13-byte format.

---

## 7. Recommended fix order

1. **Fragment wire format (3.1)** — single biggest interop fix, ~10 lines in `BleMeshService.kt` plus comment cleanups. Without this, the router cannot pass fragmented traffic, which is the majority of real-world BLE mesh traffic above trivial chat messages.
2. **CCCD descriptor write (3.2)** — ~8 lines in `onServicesDiscovered`. Unlocks inbound traffic on half of the GATT connections.
3. **`WRITE_TYPE_NO_RESPONSE` (3.3)** — 1 line. Do this at the same time as 3.2 since both live in the GATT client setup.
4. **MTU-aware split before send (3.4)** — wire `fragmentationManager.split()` into `writeToConnection`. Most invasive fix in this list; but once 3.1 is correct the fragment-production code just mirrors the reference.
5. **`ROUTABLE_TYPES` additions (4.1)** — one-line additions per missing type.
6. **TCP keep-alive on bridge sockets (4.2)** — 2 lines.
7. Everything else is P2/P3 and can be addressed in follow-up.

After the P0 items, the router should interoperate correctly with both reference clients for all real-world traffic patterns.
