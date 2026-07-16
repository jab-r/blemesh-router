# Phone Noise-keepalive plan × router — interop assessment

Assessed 2026-07-16 against `../loxation-sw/HANDSHAKE_CHURN_PLAN.md` (H1–H6; iOS
implemented on `feat/minimize-handshake-churn`, Android mirror planned in
`../loxation-android/MINIMIZE_HANDSHAKE_CHURN.md`, lockstep ship). The phone plan adds a
Noise-inner keepalive (`NoisePayloadType 0x07` inside directed 0x12, ~every 20s per idle
session), retransmits the directed 0x40 readiness frame instead of tearing sessions down,
decouples phone session lifetime from presence (~600s), and later (H6) prefixes the 0x10
payload with a pattern-discriminator byte.

## Verdict: no router mirror required

- The router holds **no Noise sessions** and never ported the encrypted-channel-stale
  reaper (`router/LocalIdentity.kt` — the X25519 key exists only for PeerID derivation;
  there is no `MeshStateStore`, no `ENCRYPTED_CHANNEL_STALE`). The plan's churn sources
  S1–S3 have no router analog.
- 0x10/0x12 payloads are **opaque**: only the outer header is decoded
  (`protocol/BinaryProtocol.kt`), and both types are `NO_COMPRESS`, so bytes ride
  verbatim. Inner type 0x07 and the H6 0x10 discriminator byte are invisible here.
- Keepalives **cannot pollute gossip**: 0x12 is not in `GOSSIP_STORED`
  (`model/MessageType.kt`) and every gossip store additionally requires broadcast;
  keepalives are directed.
- H3's longer phone session lifetime is invisible to the router as long as the phones'
  announce cadence stays ~30–45s — router presence TTLs assume that
  (`HOME_ROUTER_TTL_MS` / `REGION_MEMBER_TTL_MS` = 90s, "~3 missed 30s intervals").

## How a keepalive traverses the router

- **Relayed** like any directed 0x12: hop-by-hop with 50–200ms jitter
  (`BlemeshProtocol.shouldRelay` — every type relayable since 2a32c0c, TTL>1 gate only).
- **Presence side effect**: any BLE-origin packet stamps `regionMembers`
  (`mesh/BleMeshService.kt`, processIncomingPacket) — a keepalive therefore refreshes the
  router's presence view of its *sender* and keeps ROUTER_HOME advertisements alive.
  That is correct: it is presence proof, mirroring the phones' own treatment.
- **Never store-and-forwarded or gossip-stored** (`SNF_ELIGIBLE` / `GOSSIP_STORED`
  exclude 0x12).
- **DM-queued when the recipient is absent** (route-or-retry,
  `router/MeshRouterService.kt`): bounded at 32 frames/recipient, 120s max age. At 20s
  cadence that is a steady ~6 queued keepalives per idle absent pair, replayed FIFO when
  the recipient's announce reveals its new router. Bounded and by design — the router
  cannot distinguish a keepalive from a real DM (payload opaque).
- **RETRY-STORM diagnostic**: fixed-window semantics (`router/RetryStormTracker.kt`,
  2026-07-16) — a steady 20s cadence yields ≤2 originations per 30s window and never
  warns; a genuine storm (≥3 in one window) still warns each window it persists.
  *Before this fix* the counter reset only on a 30s quiet gap, so keepalives would have
  logged a bogus perpetual warning per idle phone pair.

## Cross-repo flags (report back to loxation-sw / loxation-android)

1. **H2 0x40 retransmits must carry a fresh wire timestamp.** Mesh dedup keys on
   `senderId-wireTimestamp-type` (router `mesh/BleMeshService.kt`
   buildDeduplicationKey; phones identical). A byte-identical retransmit is swallowed at
   the first dedup point — router or phone relay — and never reaches the peer.
2. **DM-queue flush vs Noise nonce ordering (watch item; pre-existing, made more frequent
   by keepalives).** Replayed queued 0x12s are FIFO per recipient, but if newer-nonce
   frames reach the recipient via its new route *before* the flush replays older ones,
   each stale frame is one consecutive decrypt failure against the phones' 5-failure
   teardown threshold (up to ~6 can be queued). The race window is thin (the flush is
   triggered by the same announce that enables new targeted sends); worth watching in the
   venue soak, not a code change now.
3. **H2 retransmits will legitimately log one RETRY-STORM line** (≤3 directed 0x40s in
   ~15s at MAX_TTL crosses the count-3 threshold once). That is a correct signal of real
   frame loss, not noise.
