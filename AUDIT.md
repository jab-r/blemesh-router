# BLE Mesh Router — Correctness Audit

Audit target: `/Users/jon/Documents/GitHub/blemesh-router` (HEAD = `2a1f701`).
References: `../loxation-android` (Kotlin, canonical) and the protocol spec at `../loxation-android/BLEMESH+LOXATION_protocol.md`.
Audit date: 2026-05-01.
Supersedes: prior audit dated 2026-04-21 (commit `bb3d385`).

Severity scale:
- **P0** — breaks interop. Router will silently drop or corrupt traffic to/from reference clients on a common path.
- **P1** — degrades reliability or loses specific message classes. Router works for some traffic but not all.
- **P2** — robustness/correctness concern, not currently an interop break.
- **P3** — cosmetic / parity-noted / future-proofing.

---

## 1. Executive summary

Since the April 2026 audit, the router has shipped 9 commits (`1297218` … `2a1f701`) that close every P0 and P1 finding. Wire format, GATT plumbing, MTU-aware fragmentation, the WiFi bridge's routable-types whitelist, and TCP keepalive on bridge sockets are all correct. New transports (Wi-Fi Aware in `c20ea66`, Wi-Fi Direct, mDNS LAN discovery in `d31283c`) and the field-test fixes for stale BLE address mappings and Pixel NAN/P2P interface contention (`b96f085`) are correctly implemented.

**The router is interop-correct against loxation-android and loxation-sw on all paths verified.** Initial findings were limited to one P2 robustness issue (fragment-dedup key asymmetry on the WiFi-to-BLE injection path) and two P3 notes; the P2 and one P3 have since been fixed in this commit. The last P3 (`isValidResponse` dead code) was resolved in June 2026: both apps landed their link-peer-keyed RSR flood gates, the router deliberately does NOT gate (collected ttl=0 replays are its cross-segment backfill input by design), and the dead `RequestSyncManager` was deleted so a signature that had drifted from the apps' can't be mis-wired later.

| Severity | Count this audit (initial) | Remaining after §4 fixes | Count prior audit |
|---|---|---|---|
| P0 | **0** | **0** | 4 |
| P1 | **0** | **0** | 4 |
| P2 | 1 | **0** | 5 |
| P3 | 2 | **0** (last one resolved June 2026, see §4.3) | 4 |

---

## 2. Status of prior findings

| Prior § | Severity | Description | Status | Verified at |
|---|---|---|---|---|
| 3.1 | P0 | FRAGMENT wire format parsed as 10-byte header instead of 13 | **FIXED** | `mesh/BleMeshService.kt:481–487` (parse), `:482` (size guard `< 13`), `:542–549` (dedup key uses 2-byte index), commit `1297218` |
| 3.2 | P0 | No CCCD descriptor write — central-role inbound notifications never arrive | **FIXED** | `mesh/BleMeshService.kt:399–408` writes `ENABLE_NOTIFICATION_VALUE` to `00002902-…` after `setCharacteristicNotification(true)` |
| 3.3 | P0 | Client writes don't set `WRITE_TYPE_NO_RESPONSE` | **FIXED** | `mesh/BleMeshService.kt:389` (in `onServicesDiscovered`) and `:621` (per-write) |
| 3.4 | P0 | No MTU-aware splitting before send; `BleMeshFragmentationManager.split()` had no caller | **FIXED** | `mesh/BleMeshService.kt:570–612` splits when `encoded.size > maxSingleWrite`; MTU is requested at `:364` (`requestMtu(512)`) and stored per connection |
| 4.1 | P1 | `ROUTABLE_TYPES` whitelist dropped LEAVE, NOISE_IDENTITY_ANNOUNCE, DELIVERY_STATUS_REQUEST, LOXATION_QUERY/CHUNK/COMPLETE | **FIXED** | `router/MeshRouterService.kt:57–75` — all five missing types are now present |
| 4.2 | P1 | Bridge had a 45-second stale-connection reaper but no keepalive | **FIXED** | `transport/WifiBridgeTransport.kt:112` (outbound) and `:159` (inbound) set `socket.keepAlive = true` |
| 4.3 | P1 | No loop tag — dedup-only loop avoidance | **STILL PRESENT, accepted** | `router/MeshRouterService.kt:269–287` — for the current 2–3 router deployment scope this is fine; revisit if topology grows |
| 4.4 | P1 | Bridge does not refresh TTL when crossing transports | **SUPERSEDED (e1ea500)** | The bridge now *spends* one TTL unit per crossing (floor 0) and clamps inbound packets arriving at MAX_TTL to MAX_TTL−1, so a hop through the router can't masquerade as a direct connection — `router/MeshRouterService.kt:265–277` (outbound), `:391–398` (inbound clamp). Still a single logical mesh: no refresh at the boundary |
| 5.1 | P2 | `RequestSyncManager.isValidResponse()` is defined but never called | **RESOLVED (June 2026)** | Both apps landed their RSR flood gates; the router deliberately does not gate (see §4.3) and `RequestSyncManager` has been deleted outright |
| 5.2 | P2 | `handleFragment` dedup-key asymmetry (1-byte vs 2-byte index) | **FIXED** | `mesh/BleMeshService.kt:484–485` (parse) and `:545` (dedup) both use 2-byte big-endian |
| 5.3 | P2 | Jitter source not uniform (`abs(nanoTime) % range`) | **STILL PRESENT, parity** | `protocol/BlemeshProtocol.kt`; bias is negligible at 150 ms range |
| 5.4 | P2 | `injectPacketFromWifi` uses a 3-part dedup key while BLE inbound uses a 5-part key for FRAGMENT | **STILL PRESENT** — see §4.1 below |
| 5.5 | P2 | `remotePeerToRouter` learns from any sender, no TTL/eviction | **N/A** — this map no longer exists in `MeshRouterService.kt`; routing now relies on per-transport `connectedPeerIDs()` lookups |
| 5.6 | P2 | Reconnect loop is unbounded, flat 5 s | **STILL PRESENT** — see §4.2 below |
| 6   | P3 | Header comments / Kdoc claimed wrong layouts | **FIXED** | `mesh/BleMeshFragmentationManager.kt:14` now documents `[id:8][index:2 BE][total:2 BE][originalType:1]`; `router/MeshRouterService.kt:52–55` matches |

---

## 3. Verified correct (parity with reference)

The following were re-checked against the reference and the spec; all are byte-for-byte and behaviour-for-behaviour parity:

### Wire format
- 14-byte header layout, big-endian — `protocol/BinaryProtocol.kt:70–80`.
- Flag bits (`FLAG_HAS_RECIPIENT=0x01`, `FLAG_HAS_SIGNATURE=0x02`, `FLAG_IS_COMPRESSED=0x04`) and `BROADCAST_ADDRESS = 0xFFFFFFFFFFFFFFFF` — `model/BlemeshPacket.kt`.
- `MAX_TTL = 7` — `model/BlemeshPacket.kt:24`.

### Codec & framing
- Compression framing: 2-byte big-endian `originalSize` prefix with 4-byte fallback on decode — `protocol/BinaryProtocol.kt:87–91, 171–196`.
- `NO_COMPRESS_TYPES` matches reference, including the deliberate exclusion of `NOISE_IDENTITY_ANNOUNCE (0x13)` (which is therefore compressed) — `protocol/BinaryProtocol.kt:29–39`.
- `MessagePadding` block sizes `{256, 512, 1024, 2048, 4096}`, 1-byte vs 3-byte trailer split at 256 — `protocol/MessagePadding.kt`.
- `CompressionUtil`: ≥ 100 byte threshold, 0.9 unique-byte entropy test over first 256 bytes, zlib-wrapper first with raw DEFLATE fallback on decode — `protocol/CompressionUtil.kt`.

### Identity, gossip, sync
- `PeerID = first 8 bytes of SHA-256(noise static pub key)`, big-endian Long round-trip — `model/PeerID.kt:37–52`; derivation at `mesh/BleMeshService.kt`.
- `PacketIdUtil` SHA-256 input ordering `[type:1 | senderId:8 BE | timestamp:8 BE | payload]`, 16-byte truncation — `sync/PacketIdUtil.kt:14–26`.
- `GCSFilter` MSB-first bitstream, unary `q` + 0 + `p`-bit `r`, delta encoding, `h64` = first 8 bytes of SHA-256 masked to 63 bits, `m = n << p`, 10% trim loop — `sync/GCSFilter.kt`.
- `SyncTypeFlags` bit indices 0–9, little-endian byte packing, trailing-zero trim — `sync/SyncTypeFlags.kt`.
- `RequestSyncPacket` TLV tags `0x01..0x05`, M as uint32 BE, 1024-byte filter cap — `sync/RequestSyncPacket.kt`.
- `AnnouncementData` encodes only `0x01/0x02/0x03`, decodes all seven defined tags — `model/AnnouncementData.kt`.
- `REQUEST_SYNC` sent with `ttl = 0` so it is never relayed — `sync/GossipSyncManager.kt:249, 266`.

### BLE plumbing
- CCCD descriptor write after `setCharacteristicNotification` — `mesh/BleMeshService.kt:399–408`.
- `WRITE_TYPE_NO_RESPONSE` for client-role writes — `mesh/BleMeshService.kt:389, 621`.
- `requestMtu(512)` after connection-state change; negotiated MTU stored on the per-peer `PeerConnection` — `mesh/BleMeshService.kt:364`.
- Server-role notify path uses `notifyCharacteristicChanged` on the local characteristic — `mesh/BleMeshService.kt`.
- Outbound fragmentation: `BleMeshFragmentationManager.split()` is called when `encoded.size > maxSingleWrite`; each piece wrapped in a `FRAGMENT (0x05)` packet with the 13-byte header — `mesh/BleMeshService.kt:570–612, 586–594`. Inter-fragment 6 ms pacing delay at `:609`.

### Relay & dedup
- `shouldRelay = isRelayablePacketType && ttl > 1`; `NOISE_ENCRYPTED (0x12)` excluded — `protocol/BlemeshProtocol.kt:25–30`.
- TTL decremented *before* rebroadcast (`packet.withDecrementedTTL()`) — `mesh/BleMeshService.kt:474, 504`.
- Relay jitter window 50–200 ms — `protocol/BlemeshProtocol.kt:33`.
- Dedup key for non-fragments: `"{senderId}-{timestamp}-{type}"` — `mesh/BleMeshService.kt:548`.
- Dedup key for fragments includes `fragmentID` and `index`: `"{senderId}-{timestamp}-{type}-{fragmentID}-{index}"` — `mesh/BleMeshService.kt:543–546`.
- Service / characteristic UUIDs match `BLE_UUIDS.md`: `F47B5E2D-…` / `A1B2C3D4-…`.

### WiFi bridge
- Single global `bridgeDeduplicator` (`maxAge = 5 min`, `maxEntries = 5000`, matching the references' 5-minute dedup window) used across TCP, Aware, and Direct — `router/MeshRouterService.kt:68–73`.
- Bridge dedup keys distinguish direction: `"ble2br-…"` (`:224`) and `"br2ble-…"` (`:274`) so the BLE→bridge and bridge→BLE paths never alias.
- `routeBlePacketToBridge` skips `REQUEST_SYNC` with `ttl=0` (local-only) at `:222`.
- `routeBlePacketToBridge` skips bridging when the recipient is already known-local at `:234–236`.
- `routeBridgePacketToBle` re-broadcasts to *other* router peers across all transports, excluding the originating peer — `:282–286`.
- Stale BLE address mapping: when a direct ANNOUNCE (TTL=7) arrives for a known peer at a different address, the prior address is evicted before the new mapping is recorded — `mesh/BleMeshService.kt:515–533`. This was the field-test fix in `b96f085` and resolves the duplicate-peer issue caused by Loxation/iOS rotating BLE resolvable private addresses every ~15 min.
- Disconnect cleanup: `handleDisconnection` removes the peer from `addressToPeerID`, `peerIdToAddress`, fires `onPeerDisconnected`, and calls `gossipSyncManager.removeAnnouncementForPeer` — `mesh/BleMeshService.kt:642–649`.

### New transports (verified correct)
- **WifiAwareTransport** — service `blemesh-router-v1`; `serviceSpecificInfo` carries the 8-byte PeerID; lexicographic compare picks server vs client; length-prefixed framing matches the TCP transport; socket teardown on `onLost`.
- **WifiDirectTransport** — present and structured the same way as WifiAwareTransport; `TransportSelector.kt:33–50` only enables it when Wi-Fi Aware is unavailable, which avoids the Pixel NAN/P2P interface conflict that motivated `b96f085`.
- **LanPeerDiscovery** — registers `_blemesh-router._tcp.` with TXT record `peer_id=<hex>`; multicast lock acquired on start and released on stop.
- **TransportSelector** — runs TCP + (Aware *xor* Direct) concurrently; the global bridgeDeduplicator handles the multi-path case correctly.

### Manifest
- Foreground service type `connectedDevice`; `MeshRouterService` is *not* exported; `BootReceiver` is exported with the standard `BOOT_COMPLETED` filter; permissions match the spec (`NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, etc.).

---

## 4. Active findings

### 4.1 P2 — Fragment dedup key asymmetry on `injectPacketFromWifi` — **FIXED**

**Where it was.** `mesh/BleMeshService.kt:182` previously built a 3-part dedup key `"{sender}-{ts}-{type}"` for every WiFi-injected packet, while the BLE inbound path at `:542–549` builds a fragment-aware 5-part key `"{sender}-{ts}-{type}-{fragmentID}-{index}"` for `FRAGMENT (0x05)` packets. Both paths store into the same `deduplicator`, so the asymmetry meant a packet seen on one path could miss dedup on the other.

**Blast radius (had it bitten).** Each outer FRAGMENT packet has its own `timestamp`, so under normal traffic the 3-part key still distinguished legitimate fragments. The asymmetry only mattered when the same fragment outer-packet bytes arrived via both paths — in which case `BleMeshFragmentationManager.addFragment` would have done duplicate work but not corrupted state.

**Fix applied.** `injectPacketFromWifi` now calls `buildDeduplicationKey(packet)` so both ingress paths share key construction. Verified at `mesh/BleMeshService.kt:182–183`.

### 4.2 P3 — Reconnect loop has no exponential backoff — **FIXED**

**Where it was.** `transport/WifiBridgeTransport.kt:104–135` retried with a flat 5 s `RECONNECT_DELAY_MS` and recursed via `connectToHost(host, remotePort)` indefinitely on failure. A permanently unreachable peer ate ~12 connect attempts per minute for the lifetime of the service.

**Fix applied.** The recursive retry has been replaced with an internal `while (isRunning)` loop using exponential backoff: starts at 5 s (`INITIAL_RECONNECT_DELAY_MS`), doubles after each failure, caps at 5 min (`MAX_RECONNECT_DELAY_MS`), and exits the loop on a successful `registerConnection`. Verified at `transport/WifiBridgeTransport.kt:40–42, 104–138`.

### 4.3 P3 — `RequestSyncManager.isValidResponse()` is defined but never called — **RESOLVED (June 2026): deleted; not gating is now a deliberate divergence**

The original rationale ("parity with loxation-android, pending a paired change") no longer holds: both apps have since wired link-peer-keyed RSR flood gates (Android `BLEMeshService.kt:765`, iOS `BLEMeshService.swift:2108`). The router intentionally does **not** follow them — it is a bridge, and the ttl=0 gossip replays it collects from local phones are its only cross-segment backfill input (`router/MeshRouterService.kt:265–277`); a gate would drop exactly that traffic, while buying ~nothing (the router ingests all fresh public packets at any TTL by design; its defenses are dedup, freshness windows, and capacity-bounded stores). `RequestSyncManager` was deleted outright rather than left dormant: its `isValidResponse(from)` signature had drifted from the apps' `isValidResponse(from, isRSR)`, so keeping it invited a future mis-wire with wrong semantics. The residual note stands: an unauthenticated peer can still make the router enumerate its packet store with unsolicited `REQUEST_SYNC` — accepted, since responses are gossip data with no secrets and the per-request cost is small.

---

## 5. Out-of-scope but flagged for follow-up

These are listed for completeness; none are recommended to act on right now.

- **Bridge loop avoidance** (`router/MeshRouterService.kt:269–287`). Still relies entirely on `bridgeDeduplicator` to terminate A–B–C–A cycles. Acceptable for ≤ 3-router deployments. If the bridge topology grows to 4+ routers, add a `routers visited` TLV (or per-packet bridge-only envelope) and check it on ingress.
- **TTL across the bridge** (`router/MeshRouterService.kt:265–277`). Since `e1ea500` the bridge *spends* one TTL unit per crossing (floor 0) and clamps inbound MAX_TTL arrivals to MAX_TTL−1 (`:391–398`) so a hop through the router can't masquerade as a direct connection. Still no refresh at the boundary, which caps the practical reach of a multi-segment mesh. If the goal becomes "router resets the segment counter," add a deliberate refresh at the bridge boundary plus a guard against infinite amplification (e.g. only refresh once per bridge hop and track that in the same routers-visited TLV from the prior bullet).
- **Wi-Fi Direct transport** is shipped but only activates when Wi-Fi Aware is unavailable. Its on-device test coverage is therefore thin. If you intend to support pre-Aware devices in production, plan a dedicated field test for the Direct path.

---

## 6. Recommended next steps

1. ~~Fix §4.1~~ — done in this commit.
2. ~~Fix §4.2~~ — done in this commit.
3. ~~Decide on §4.3~~ — resolved June 2026: `RequestSyncManager` deleted; the router deliberately does not gate RSRs (see §4.3).
4. **Field test Wi-Fi Direct** if pre-Aware (Pixel-and-friends only) deployments are in scope.

Everything else listed in §2 / §5 is accepted-as-is for this release.
