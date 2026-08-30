# MeshCore LoRa leg — router-side feasibility + implementation plan

**Status: PLAN (2026-08-29).** Router-side answer to `loxation-sw` PR #240
(`docs/LORA_MESHCORE_FEASIBILITY.md`, merged 2026-08-30). Companions: `FIND_BEACON_BRIDGING.md`
(the backbone gate + gossip store this leg hangs off), `FIND_MODE_SPEC.md` (snapshot of the
canonical spec), `BACKBONE_PATH_ROUTING_SPEC.md` (the `ROUTER_CAPS` extension point).

Only §3 item 1 (the `NO_COMPRESS` lockstep fix) has landed. Everything else is a plan.

---

## 0. Context

`loxation-sw` PR #240 abandons the Meshtastic/TAK direction and has the **phone** drive a MeshCore
*companion* radio over BLE (Nordic-UART-shaped GATT service, MIT firmware, companion protocol v13).
The 110 B sealed+signed Find beacon rides as a `CMD_SEND_CHANNEL_DATA` (0x3E) datagram — ≤ 163 B,
app-defined `data_type`, flood path — on a Loxation channel and arrives as
`RESP_CODE_CHANNEL_DATA_RECV` (0x1B) → `FindBeaconReceiver.ingest(transport: "lora")`. Phones will
carry a **v2** beacon on that leg (`sentEpoch` inside the AEAD, 114 B) because the hop is
store-and-forward. Phase 0 there is hardware only; phases 1–2 add `MeshCoreRadioService` +
`MeshCoreCodec` + beacon v2. §2.5 of that doc already anticipates the router: *"A Loxation router
with a MeshCore repeater radio attached extends venue reach … the router team's Tier-3 backbone
gains a LoRa hop."*

The question this answers: **could devices running blemesh-router interop with MeshCore devices
as routers?**

## 1. Verdict

**Yes — as a gateway endpoint on the MeshCore mesh, for Find beacons only. No — as a MeshCore
repeater. No — as a LoRa backbone for general blemesh traffic.**

| Reading of "interop as routers" | Verdict | Why |
|---|---|---|
| Router **owns a companion radio** and bridges `0x4A` BLE-mesh / WiFi-backbone ⇄ LoRa | **Feasible, good fit** | Same channel key + `data_type` as the phones' leg ⇒ phones-with-pucks, routers-with-pucks and every stock MeshCore repeater interoperate with zero MeshCore-side change. The router stays a pure carrier (no find key, decides on the cleartext header only) — exactly its role today. |
| Router **acts as a MeshCore repeater/router** (relays LoRa packets) | **No** | An Android device has no LoRa radio. Repeating is the *board's* firmware role; companion firmware does not forward (`MyMesh::allowPacketForward` returns the repeat pref, off for companions — "companions never repeat"). LoRa multi-hop = flash a $20 board with stock repeater firmware. Nothing for this repo. |
| Router carries **announces / gossip / DMs / Noise** over LoRa | **No** | 163 B datagram cap; ~0.4–0.5 s of air per ~130 B packet at the recommended SF7/BW62.5; EU 868 duty 10 % ⇒ ≈ 12 packets/min per radio *total*. Only the 114 B v2 beacon (and a future SOS broadcast form) fits. The backbone stays WiFi; LoRa is a reach extension for the position plane. |

**What it buys.** One puck per **router** covers every phone in that router's venue (phones need no
radio), and the Tier-3 backbone gains a LoRa leg between venues that share no WiFi. **Honest
capacity:** at a 5 % airtime budget (~6 datagrams/min) and ≥ 60 s per sharer, one router uplinks
~6 concurrently-beaconing sharers before fair-share coalescing kicks in — "a handful of people per
venue", not festival scale. Cross-venue reach at scale remains the cell leg's job.

## 2. What was verified (sources)

**Router (this repo)**
- `router/MeshRouterService.kt`: `routeBlePacketToBridge` — BLE-origin path, crossing allowlist,
  `FindBeaconBridgeGate` via `offerToBackboneGate`, `drainFindBeaconGate` on the 15 s DM sweep;
  `routeBridgePacketToBle` — loop guard, dedup, TTL clamp, `bleMeshService.injectPacketFromWifi`,
  `forwardBroadcastOnBackbone`; `broadcastToBackbone` / `originVisited`; `ROUTER_CAPS`
  (`CAPS_VERSION 0x01`, `CAPS_BIT_PATH_TAG 0x01`) as the extension point for a new capability bit.
- `mesh/BleMeshService.kt`: already a GATT **central** (`connectGatt`, `gattClientCallback`,
  `setCharacteristicNotification`, `writeCharacteristic`) — the idioms a MeshCore radio client
  needs; `injectPacketFromWifi` = dedup → gossip store → BLE flood.
- `transport/RouterTransport.kt`: peer-keyed, full-packet transport interface — the LoRa leg does
  **not** fit it (no peer identity, no full packets); it is a beacon-only side channel.
- `sync/GossipSyncManager.kt`: `LatestPerPeerStore` keyed by `PeerID`, 900 s horizon, capacity
  300; `sync/PacketIdUtil.kt` (id = SHA-256(type‖sender‖wireTs‖payload)).
- `protocol/CompressionUtil.kt`: `shouldCompress` = size ≥ 100 ∧ unique-byte ratio < 0.9;
  `compress` returns null when DEFLATE does not shrink.
- `util/MessageDeduplicator` (string-keyed, windowed) — reusable for blob-level dedup.

**MeshCore (`meshcore-dev/MeshCore` main, MIT)**
- Companion host interfaces (`examples/companion_radio/main.cpp`): BLE `SerialBLEInterface`
  (`BLE_PIN_CODE`), USB serial `ArduinoSerialInterface` (`ENABLE_USB_INTERFACE`), WiFi TCP :5000
  `SerialWifiInterface` (`WIFI_SSID`/`WIFI_PWD` **compile-time**), UART (`SERIAL_RX`), Ethernet.
- Heltec V3 flasher envs: `_companion_radio_usb`, `_ble` (`BLE_PIN_CODE=123456`,
  `OFFLINE_QUEUE_SIZE=256`), `_wifi` (hard-coded SSID). nRF52 pucks (T1000-E, RAK4631) have no WiFi.
- Serial framing (`src/helpers/ArduinoSerialInterface.cpp`): host→radio `'<'` + u16 LE length +
  frame; radio→host `'>'` + u16 LE length + frame; 115200 baud.
- `MyMesh.cpp`: `MAX_CHANNEL_DATA_LENGTH = MAX_FRAME_SIZE − 9` (163); TX queue full ⇒
  `ERR_CODE_TABLE_FULL` (3); `0x1B` layout
  `[0x1B][snr×4:i8][rsvd:2][chan][path_len][data_type u16 LE][len][payload]`; offline queue evicts
  the oldest *channel* message first when full; `allowPacketForward` = repeat pref.
- `docs/companion_protocol.md`: service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, RX `…0002`
  (write), TX `…0003` (notify); one frame per write/notification; `data_type` is an application
  id (`0xFF00–0xFFFE` dev, registered ranges by PR); inbound = `PUSH_CODE_MSG_WAITING` (0x83) →
  `CMD_SYNC_NEXT_MESSAGE` (0x0A). Doc self-describes as partial — layouts of `SET_RADIO_PARAMS`,
  `SET_DEVICE_TIME`, `DEVICE_QUERY` to be confirmed against `meshcore.js`/firmware (as #240 says).

**Phones**
- iOS `loxation/Services/FindBeaconCodec.swift`, Android `findmode/FindBeaconCodec.kt`: beacon is
  **110 B** `[ver=1][keyId:4][nonce:12][ct:13][tag:16][sig:64]`, AD = ver‖keyId, Ed25519 over the
  46 B prefix. Header parse (`FindBeaconCodec.header`) requires exact length + version 1.
- Both gossip stores key by cleartext **keyId** (iOS `latestFindBeaconByKeyId`, Android
  `latestFindBeaconByKeyId`) — survives PeerID rotation.
- Both put 0x4A on `noCompressTypes` (iOS `Protocols/BinaryProtocol.swift`, Android
  `protocol/BitChatProtocol.kt`) with the comment "Android + router mirror it".
- No MeshCore code exists on either phone yet (docs only). Android's `LORA_FIND_SOS_LEG.md` is the
  *superseded* TAK design (uid `LOX-<keyId>`, CoT XML) — not what the router should target.

## 3. Two lockstep drifts found on the way (fix regardless of LoRa)

1. **`NO_COMPRESS` fork — FIXED 2026-08-29.** `MessageType.NO_COMPRESS` omitted `FIND_BEACON`
   citing a 48 B beacon; the shipped beacon is 110 B and both phones pin 0x4A on the list.
   Behaviourally harmless on the wire (110 random bytes *do* pass `shouldCompress`, ≈ 0.81 < 0.9,
   but `compress()` returns null because DEFLATE doesn't shrink ciphertext — the packet went out
   raw), but the interop-pinned list was forked and
   `FindBeaconPolicyTest.neverCompressedBecauseItIsUnderTheSizeThreshold` asserted a stale spec.
   Now: 0x4A is on `NO_COMPRESS`, pinned by `FindBeaconPolicyTest.onTheLockstepNoCompressList`;
   `FIND_BEACON_BRIDGING.md` §1 corrected. The `FIND_MODE_SPEC.md` snapshot still carries the
   pre-signature §2 text and should be regenerated from `../loxation-sw/docs/FIND_MODE_SPEC.md`.
2. **Store/gate keyed by PeerID; phones key by keyId — OPEN.** `GossipSyncManager.findBeacons`
   and `FindBeaconBridgeGate` key on the wire `senderId`. For the LoRa leg this is a **hard
   prerequisite**: a LoRa-received blob has no PeerID, so the router injects it under its *own*
   senderId — PeerID keying would collapse every remote sharer into one slot in both the store
   and the 30 s gate. keyId keying also matches the receiver's identity model (spec §1: "the
   ephemeral wire senderID is never trusted for identity"). Phase A below.

## 4. Numbers that shape the design

| Quantity | Value | Source / derivation |
|---|---|---|
| Beacon blob | v1 110 B · v2 114 B | codecs; #240 §2.3 |
| Datagram payload cap | 163 B | `MAX_CHANNEL_DATA_LENGTH` |
| BLE write for one beacon | 3 + 2 + 114 = 119 B (+ path byte) | `[0x3E][chan][0xFF][data_type u16][blob]` — one GATT write at MTU ≥ 185 |
| On-air size | ≈ 140 B (MeshCore header + path + channel hash + 2 B MAC + AES-padded 116 B) | `packet_format.md` |
| Airtime at SF7 / BW62.5 / CR5 | ≈ 0.45 s (symbol 2.048 ms, ≈ 200 symbols + preamble) | matches #240 "0.4–0.5 s" |
| EU 868 duty limit | 10 % ⇒ ≈ 13 datagrams/min | regulatory |
| Router airtime budget (proposal) | **5 % ⇒ 6/min** aggregate, token bucket cap 3 | this plan |
| Per-sharer LoRa interval (proposal) | **≥ 60 s** (coalesce, newest wins) | vs 30 s on the WiFi backbone |
| Fair-share capacity | ≈ 6 concurrently-beaconing sharers per router | 6/min ÷ 1/min |
| Companion offline queue | 256 frames | `_ble` build flag |

## 5. Architecture of the leg

```
 phones ──BLE──> Router A ──WiFi backbone──> Router B ──BLE──> phones
                   │ (BLE-origin 0x4A, v2 only, ≥60 s/keyId, ≤6/min)
                   ▼
            MeshCore companion puck (GATT central from the router)
                   │ CMD_SEND_CHANNEL_DATA slot 1, data_type 0xFF4A, flood
                   ▼
   ~~~ LoRa ~~~ stock MeshCore repeaters (optional, transparent) ~~~ LoRa ~~~
                   │
                   ▼
            puck on Router C  (or a phone's own puck — same datagram)
                   │ RESP_CODE_CHANNEL_DATA_RECV → wrap as 0x4A (senderId = C)
                   ▼
   C: injectPacketFromWifi (BLE flood + keyId gossip store) + backbone (origin = C)
```

The leg is **not** a `RouterTransport`: it has no router-peer identity, carries no full packets, and
must never see anything but 0x4A. It is a side channel hung off the two existing beacon decision
points (`routeBlePacketToBridge` for uplink, an injection path mirroring `routeBridgePacketToBle`
for downlink).

## 6. Phase A — prerequisites (no hardware; small)

### A1. `model/FindBeaconHeader.kt` (new, pure Kotlin)
```kotlin
object FindBeaconHeader {
    const val KEY_ID_LENGTH = 4; const val NONCE_LENGTH = 12
    const val HEADER_LENGTH = 1 + KEY_ID_LENGTH + NONCE_LENGTH          // 17
    const val V1_PAYLOAD_LENGTH = 110   // [ver][keyId:4][nonce:12][ct:13][tag:16][sig:64]
    const val V2_PAYLOAD_LENGTH = 114   // v1 + sentEpoch:u32 inside the AEAD (#240 §2.3)
    data class Parsed(val version: Int, val keyIdHex: String, val nonceHex: String) {
        val blobKey: String get() = keyIdHex + nonceHex   // LoRa dedup key
    }
    fun parse(payload: ByteArray): Parsed?
    // null when size < 17, version == 0, version 1 && size != 110, version 2 && size != 114;
    // version ≥ 3: header-only parse (carrier neutrality — the router must not block a future
    // format it cannot judge)
}
```
### A2. `model/MessageType.kt`
- ~~Add `FIND_BEACON` to `NO_COMPRESS`~~ — done (§3 item 1).
- `GOSSIP_STORED` / `SNF_ELIGIBLE` KDoc: "newest-per-sender" → "newest-per-keyId".
### A3. `sync/GossipSyncManager.kt`
- `LatestPerPeerStore` → `LatestPerKeyIdStore`, map key `String`; semantics unchanged (monotonic
  on `timestamp`, oldest-fix-first eviction, hard capacity, `@Synchronized`).
- Insert: `FindBeaconHeader.parse(packet.payload)?.keyIdHex ?: return` (unparseable = dead cargo
  for phones too; Android drops the same way). Drop the `PeerID.fromLongBE` gate.
- Config KDoc: "per sender" → "per keyId (sharer)".
### A4. `router/FindBeaconBridgeGate.kt` + `MeshRouterService.kt`
- `offer(key: String, …)`, `slots: ConcurrentHashMap<String, Slot>`; KDoc: a slot is a *sharer*
  (keyId) so a PeerID rotation mid-window cannot buy a second crossing.
- `offerToBackboneGate`: key = parsed keyId hex, falling back to the sender PeerID string when
  unparseable (keeps the limit instead of today's let-through). Fix the "~48 payload bytes"
  comment at `crossingType`.
### A5. Tests
- `model/FindBeaconHeaderTest.kt` (new): 110 B v1 / 114 B v2 parse byte-exact; 109/111 B v1 →
  null; version 0 → null; < 17 B → null; v3 header-only.
- `FindBeaconGossipTest`: `beacon(sender, ts)` builds a 110 B v1 blob with `keyId = sender` so
  every senderId-based assertion still holds; add `rotatedPeerIdSameKeyIdCollapsesToOneRow`,
  `unparseableBlobIsNotStored`.
- `FindBeaconBridgeGateTest`: `peer(id)` → `key(id)`; add `peerIdRotationDoesNotBuyASecondCrossing`.
### A6. Docs
- `FIND_MODE_SPEC.md` snapshot: regenerate from `../loxation-sw/docs/FIND_MODE_SPEC.md` under the
  existing "Canonical source … do not edit here" header.
- `FIND_BEACON_BRIDGING.md`: §3 "48-byte" → 110-byte; §4 "keep-latest-per-sender / 300 senders"
  → per keyId (rotation + LoRa rationale); §7 pointer to this document.
- Memory note for future sessions.

## 7. Phase B — radio link + codec (medium; one Heltec V3 or T1000-E flashed `_ble`)

### B1. Transport choice: **BLE first**
| Link | Pros | Cons |
|---|---|---|
| **BLE** (`_ble` stock build) | Every board incl. nRF52 pucks; no extra hardware; stock flasher; router already has GATT-central idioms; same path the phones use (shared codec/fixtures) | One GATT connection slot on the router's busy BLE radio; ~120 B of BLE air per beacon (negligible); PIN bonding needs a foreground activity once |
| USB serial (`_usb` build) | Frees the BLE radio; wired reliability | The router's only USB-C is its **power** — needs an OTG hub with PD pass-through and a phone that charges in host mode; new dependency (usb-serial-for-android, MIT) + `android.hardware.usb.host` |
| WiFi TCP :5000 (`_wifi` build) | Fits "router = AP" naturally; reuses socket idioms | ESP32 only; **SSID/password are compile-time** ⇒ custom firmware build per venue; not in the stock flasher |

Decision: BLE for v1; keep the codec transport-agnostic (`MeshCoreLink` interface: `send(frame)`,
`frames: Flow<ByteArray>`) so USB/TCP are drop-ins later.

### B2. `transport/meshcore/MeshCoreCodec.kt` (pure Kotlin, byte-exact tests)
Little-endian, one frame per write/notification. Encode: `APP_START`(1), `DEVICE_QUERY`(22),
`SET_DEVICE_TIME`(6), `SET_RADIO_PARAMS`(11), `SET_RADIO_TX_POWER`(12), `SET_ADVERT_NAME`(8),
`SET_CHANNEL`(32) `[0x20][slot][name:32][secret:16]`, `GET_CHANNEL`(31),
`SEND_CHANNEL_DATA`(62) `[0x3E][chan][0xFF][data_type u16 LE][payload≤163]`,
`SYNC_NEXT_MESSAGE`(10). Decode: `RESP_OK`(0), `RESP_ERR`(1)+code, `SELF_INFO`(5),
`DEVICE_INFO`(13), `CHANNEL_INFO`, `CHANNEL_DATA_RECV`(27) per the `MyMesh.cpp` layout above,
`NO_MORE_MESSAGES`, push `MSG_WAITING`(0x83). Unknown codes → `Unknown(code, bytes)` (never throw).
Layouts of `SET_RADIO_PARAMS`/`SET_DEVICE_TIME`/`DEVICE_QUERY` are **marked "verify against
meshcore.js"** and get fixture vectors captured in Phase 0 (§9). Share
`fixtures/meshcore_frames.json` with loxation-sw phase 1 — one set of vectors, three consumers.

### B3. `transport/meshcore/MeshCoreRadioClient.kt`
- BLE central to the NUS UUIDs; MTU request 185+; RX write (`WRITE_TYPE_DEFAULT`), TX notify (CCCD).
- Command queue: **one in flight**, 5 s timeout, FIFO response matching; pushes (`0x83`) handled
  out of band → `SYNC_NEXT_MESSAGE` loop until `NO_MORE_MESSAGES`.
- Reconnect: exponential 1 s → 60 s; on (re)connect run the provisioning sequence
  `APP_START` → `DEVICE_QUERY` (require protocol ≥ 13 / `0x3E` support; on
  `ERR_CODE_UNSUPPORTED_CMD` mark the radio **unusable** and surface it) → `SET_DEVICE_TIME` →
  `SET_RADIO_PARAMS` (region preset table = data, chosen in config) → `SET_ADVERT_NAME`
  (non-identifying, never `SET_ADVERT_LATLON`) → `SET_CHANNEL` slot 1 (`"loxation"`,
  `sha256("#loxation")[:16]`, verified against `GET_CHANNEL`).
- Bonding: `BluetoothDevice.createBond()` from `ui/ConfigActivity.kt` ("MeshCore radio" section:
  scan by service UUID, pick, pair with PIN `123456` / on-screen PIN, region preset, status);
  persist the MAC like `WifiCredentials`. The service reconnects headlessly thereafter.
- Thread model: a coroutine on `serviceScope`; `frames` delivered on a single consumer.
- Observability: fields on `TransportSnapshot` (connected, fw/protocol version, channel ok,
  counters tx / rx / coalesced / dropped-v1 / table-full / reconnects).

## 8. Phase C — the leg (small once A+B exist)

### C1. Uplink (BLE → LoRa), in `routeBlePacketToBridge` after the crossing allowlist, when `crossingType == FIND_BEACON`
Rules, in order:
1. **Origin-only**: BLE-received packets. Backbone-received beacons are never uplinked (optional
   election, C3). **ttl = 0 gossip replays are never uplinked** (they are backfill, not fresh
   state; the far side's own store/backbone serves them).
2. **v2-only** (`FindBeaconHeader.version == 2`): a v1 blob carries no authenticated send time, so a
   queued/delayed LoRa delivery would let the far receiver relabel it fresh (#240 §2.3). Count
   `dropped-v1`; log once per keyId.
3. **Blob dedup** on `keyId‖nonce` (`MessageDeduplicator`, 900 s window): never uplink a blob seen
   on LoRa (either direction) or already uplinked.
4. **Per-keyId ≥ 60 s + airtime budget**: `LoraUplinkScheduler` (pure Kotlin, new) =
   `FindBeaconBridgeGate` semantics (coalesce newest-wins, monotonic, router clock) **plus** a token
   bucket (cap 3, +1 per 10 s). `offer(keyId, packet, now)`; `drain(now)` releases at most
   `tokens` beacons, longest-waiting keyId first; drained on the 15 s sweep with the WiFi gate.
5. **Send**: `SEND_CHANNEL_DATA` slot 1, `path_len 0xFF`, `data_type 0xFF4A` (dev; the registered
   value once loxation-sw registers a range — one constant, lockstep with the phones), payload =
   the **bare blob** (no blemesh header — must match the phones' framing so a phone's puck can
   receive a router's datagram and vice versa). On `ERR_CODE_TABLE_FULL` re-hold and back off
   (double the bucket refill interval up to 60 s); never spin.

### C2. Downlink (LoRa → mesh), on `CHANNEL_DATA_RECV` with `data_type 0xFF4A`
1. Parse header; require v2 and 114 B (a v1 blob on LoRa is a misconfigured sender — drop, count).
2. Blob dedup (C1.3) — also stops an echo of our own uplink and a second router's re-uplink.
3. Wrap: `BlemeshPacket(type = 0x4A, ttl = MAX_TTL − 1, timestamp = now, flags = 0 (broadcast),
   senderId = myPeerID, payload = blob, signature = null)`. Phones attribute by keyId and verify
   the Ed25519 signature under the grant's key, so the router's senderId is irrelevant to them;
   `ttl = MAX_TTL − 1` avoids the direct-connection masquerade bind on injection (same reason as
   `routeBridgePacketToBle`'s clamp). For a v2 blob the receiver computes
   `fixTime = min(sentEpoch, now) − fixAge`, so `timestamp = now` cannot relabel it.
4. `bleMeshService.injectPacketFromWifi(pkt)` — dedup, keyId-keyed gossip store, local flood.
5. `broadcastToBackbone(pkt, originVisited())` through the existing 30 s WiFi gate (keyed by
   keyId after A4) so the whole backbone learns it; peer routers treat it as backbone-origin ⇒
   never re-uplinked (C1.1).

### C3. Optional uplink election (`ROUTER_CAPS` bit `0x02 = LORA`)
A router with a radio also uplinks a **backbone-received** beacon when the path origin
(`visited[0]`) advertised no LORA bit — so a venue whose origin router has no puck still gets
uplinked by a peer that has one. Loop-safe: a LoRa-injected packet's origin router *has* a radio
by construction, so it is never re-elected; C1.3 backstops. Deterministic tie-break when several
radio routers see the same origin: lowest PeerID among connected LORA-capable peers. Defer to v1.1
unless the deployment plan has puck-less routers.

### C4. Loop-freedom / cost argument
- Each unique beacon is transmitted on LoRa **at most once per radio router** (C1.3), and only by
  its origin router (C1.1) ⇒ at most once per backbone.
- A LoRa-received beacon never returns to LoRa from the receiving router (C1.1 + C1.3) nor from its
  backbone peers (not BLE-origin; C3 excluded by construction).
- Blob-level dedup is header-independent, so re-stamped headers (router-injected copies) cannot
  defeat it — unlike the `(sender, ts, type)` bridge dedup.
- Aggregate LoRa cost per router ≤ 6/min regardless of crowd size; per sharer ≤ 1/min.

### C5. Not in v1
SOS broadcast form (waits on FIND_MODE_SPEC §7 / #240 `flags` bit1 — when the phones define an
SOS beacon it rides the same leg with the gate bypassed: rare + latency-critical); MeshCore contact
DMs; raw packets (`CMD_SEND_RAW_PACKET`); app-level repeating (a stock repeater is better).

## 9. Phase 0 — hardware (one day, throwaway harness, before B)

Two Heltec V3 (`_ble` companion build from https://meshcore.io/flasher); `meshcore_py` on the Mac
as the peer. (1) `SET_CHANNEL` slot 1 with the hashtag key on both. (2) `SEND_CHANNEL_DATA` a
114 B random blob A→B: byte-exact in the `0x1B` frame, `data_type` intact, SNR/latency noted →
**fixture vectors** for B2. (3) 1 datagram / 10 s for 10 min: no `TABLE_FULL`, no drops.
(4) Round-trip `APP_START` / `DEVICE_QUERY` / `GET_DEVICE_TIME` / `GET_CHANNEL` to pin the layouts
marked "verify". (5) From the router phone: 30-line GATT spike — bond with PIN from a foreground
activity, reconnect after radio power-cycle with the service in the background. (6) Queue honesty:
disconnect the router from its puck for 30 min while A sends; reconnect; confirm queued frames
arrive via `MSG_WAITING` → `SYNC_NEXT_MESSAGE` (this is why C2 requires v2).
Go/no-go: (2) byte-exact, (5) headless reconnect.

## 10. Dependencies, risks, open questions

**On loxation-sw (phases 1–2):** beacon **v2** definition + `fixtures/find_beacon.json` v2 vectors
(the router needs only the header: same offsets, 114 B); the `MeshCoreCodec` frame fixtures;
`data_type` (dev `0xFF4A`, registered range later); channel name/key convention. The router should
consume these, not define them — hence "Phase A now, B–C after".

**Risks**
1. Partial upstream docs: three command layouts unverified until Phase 0 (4).
2. Firmware drift: `0x3E` present at protocol v13; older shipped builds answer
   `ERR_CODE_UNSUPPORTED_CMD` — gate on `DEVICE_QUERY`, surface in the UI.
3. Router BLE contention: one more GATT link on a radio that scans, advertises and holds many
   phone connections. Expect fine (Android ≥ 7 links), verify under a full cell.
4. Good citizenship on public MeshCore meshes: repeater operators can scope-filter noisy traffic;
   the 6/min budget and per-sharer floor are the argument. EU duty is a legal limit — the budget
   must be region-aware (preset table carries a duty cap).
5. Privacy: identical to the BLE model (keyId in the clear, position sealed). Never
   `SET_ADVERT_LATLON`; non-identifying advert name.
6. Pairing UX for display boards (random PIN) vs display-less (`123456`).

**Open questions for Jon**
1. Which routers get pucks — all, or one per venue (drives whether C3 election is v1)?
2. Airtime budget and per-sharer floor: 5 % / 60 s are proposals until `GetAirtime`-measured.
3. Should the router also accept **v1** on LoRa during the transition (dishonest ages) — plan says no.
4. Register a Loxation `data_type` range in `number_allocations.md` before any public-mesh use.

## 11. Verification (per phase)

- **A**: `./gradlew :app:testDebugUnitTest` green (three existing find-beacon suites after the
  re-key + the new header test); `assembleDebug`; `grep -n 48 …` finds no stale claim; snapshot
  diff against loxation-sw is header-line only.
- **B**: codec fixtures byte-exact; radio client state machine tests with a fake `MeshCoreLink`
  (timeout, queue-full backoff, reconnect provisioning, unsupported-cmd → unusable); hardware:
  bond → provision → `GET_CHANNEL` echoes the key; power-cycle the puck → headless reconnect.
- **C** (single-device log captures, per the field-test constraint): (1) phone beacons in venue 1
  → router 1 logs `BLE→lora TX` once per ≥ 60 s per keyId; (2) router 2 logs `lora→BLE inject`
  and `BLE→bridge BCAST` for it, never `BLE→lora TX` for the same blob; (3) a granted phone in
  venue 2 shows the arrow with an honest age (`fixTime` from `sentEpoch`); (4) a v1 sender is
  counted `dropped-v1`, never transmitted; (5) 10 boosted sharers in one cell ⇒ TX rate holds at
  ≤ 6/min and every sharer crosses within ~2 min (fairness); (6) a beacon that arrived via LoRa
  never reappears on LoRa from any router on the backbone.

## 12. Effort

| Phase | Size | Hardware |
|---|---|---|
| A prerequisites + docs | S (½ day) | none |
| 0 harness | S (1 day) | 2 × Heltec V3 |
| B codec + radio client + pairing UI | M (2–3 days) | 1 puck on the router |
| C leg | S–M (1–2 days) | 2 routers + pucks + 2 phones |
| C3 election, SOS form, registration, soak | later | — |
