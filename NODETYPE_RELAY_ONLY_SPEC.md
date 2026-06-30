# Announce NodeType TLV — Relay-Only Infrastructure (Jun 2026)

Cross-team lockstep spec resolving `blemesh-router` BLEMESH_FIX item 7 ("phones
perpetually fire doomed Noise handshakes at the router"). iOS implementation has
landed in loxation-sw and is the reference. Same doc in
`../loxation-android/NODETYPE_RELAY_ONLY_SPEC.md`.

## Problem

The router announces a real noise static key every 30s (its PeerID is *derived from*
that key, so it cannot omit it) but silently terminates 0x10 handshake frames addressed
to it. Both apps proactively initiate on announce when no session exists and retry
indefinitely with capped backoff — permanent airtime waste + log noise, invisible to
the router's RETRY-STORM diagnostic.

## Wire: announce TLV 0x08 `nodeType`

One byte:

| value | meaning |
|-------|---------|
| 0x00 | mobile peer (default; **absent TLV means 0x00** — every pre-flag build) |
| 0x01 | fixedRelay — infrastructure (blemesh-router / fixed beacon) |

- **Phones never emit the TLV** (mobile omits it), so phone announces stay
  byte-identical to pre-flag builds. Only the router emits `0x01`.
- Unknown future values fail OPEN to mobile (worst case = today's harmless behavior).
- ⚠️ Tag allocation: **0x05–0x07 are already reserved** by the router's
  `AnnouncementData` schema (locationId / uwbToken / timestamp, decode-side today).
  That is why nodeType is 0x08. Do not reuse 0x05–0x07.

## Semantics (protocol rule)

Apps MUST NOT initiate a Noise handshake toward a node whose announce declared
`nodeType == fixedRelay`. Additionally:

1. **Never enqueue 1:1 payloads** for it (no session will ever exist, and a non-empty
   pending queue is itself a handshake trigger — a leak around the gate).
2. **Hide it from people-list UI** and exclude it from "connected peer" counts used for
   mesh-vs-Nostr routing decisions.
3. **Relay, gossip sync, link mapping, RSR flood-gate are UNAFFECTED** — the router
   stays a first-class mesh/gossip node. (iOS: `getConnectedPeers()` for the gossip
   delegate still includes it; only the UI-facing list excludes it.)
4. Still respond normally to inbound 0x10 (a fixedRelay never sends one; the rule stays
   simple and non-exploitable).

## Security: TOFU + signing-key continuity (MANDATORY)

A honored relay-only declaration is a handshake-SUPPRESSION primitive. Announces are
self-signed (TOFU), and an attacker can re-announce a victim's PUBLIC noise key under
the attacker's own signing key with a valid self-signature — so signature validity
alone is NOT identity continuity. Suppressing handshakes toward a real phone deadlocks
the pair via the lower-PeerID-initiates tie-breaker. Therefore:

- **First contact**: accept the announced nodeType (TOFU — covers the router).
- **Known peer (pinned signing key)**: honor a nodeType *change* only when the announce
  signature verified AND its signing key equals the pinned one. Otherwise keep the
  previous value.
- **Never tear down an established session** because a flag changed.

Under these rules a forged first-contact fixedRelay only denies the forged identity
service to itself.

## Rollout order

Apps ship the honor logic first; the router starts emitting the TLV after both apps
have it in release builds. The flag is inert until emitted; unflagged routers continue
today's (harmless, noisy) behavior. No interop break at any intermediate state.

## iOS reference implementation (landed in loxation-sw)

- `AnnouncementPacket.NodeType` + TLV 0x08 encode/decode (`BlemeshProtocol.swift`);
  tolerant decoder skips unknown TLVs.
- `PeerInfo.isRelayOnly`, set in `handleAnnounce` under the TOFU/continuity rule.
- Gates: `triggerHandshake`, `triggerHandshakeBypassTieBreaker` (central),
  announce-proactive task, foreground recovery, `enqueuePending`.
- `uiPeerList()` excludes relay-only from every `didUpdatePeerList` payload;
  `getConnectedPeers()` (gossip) includes them.
- Pinned by `testAnnouncementNodeTypeTLVRoundTripAndDefault`.

## Android mapping

- `AnnouncementData`: add `nodeType` (TLV 0x08, default mobile/absent; never emit on
  phones).
- Peer model: `isRelayOnly`, set under the same TOFU/continuity rule (pinned signing
  key comparison + verified signature for transitions).
- Gate every handshake-initiation site (announce-proactive, retry chains, recovery,
  foreground) — prefer one central gate in the trigger function(s).
- Block 1:1 pending enqueue toward relay-only peers; exclude them from people-list UI
  and routing connected-counts; keep gossip/relay/link-mapping untouched.

## Relationship to the router's beacon-mode design (informational)

The router team's `beacon-mode-design-doc.md` (advertising-layer manufacturer-data
UUID in the scan response, picked up by the existing loxation BeaconScanManager generic
path) is COMPLEMENTARY, not overlapping: it makes the router *sightable* as a
registered venue beacon without client changes, but does not touch the mesh announce
and does not fix the doomed-handshake loop. nodeType is the mesh-layer half. A future
iteration can correlate the two identities (beacon UUID ↔ relay-only mesh peer) for
fixed-anchor indoor positioning.

## Router action (this repo)

- `AnnouncementData`: add `nodeType` to the model and EMIT TLV 0x08 = 0x01 in
  `encode()` (it already documents/reserves 0x05–0x07; nodeType slots in after).
- Emit only after both apps ship the honor logic (rollout order above).
- Item 7's RETRY-STORM blindspot resolves as a consequence — phones stop initiating.
