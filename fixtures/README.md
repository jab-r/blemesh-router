# Cross-platform wire-format interop fixtures

Shared regression fixtures pinning wire formats across `loxation-sw` (iOS),
`loxation-android`, and `blemesh-router`. The same files are checked into all
three repos **byte-identically** — regenerate nothing here by hand; changes
originate in the owning spec and are copied across.

## Router stage advertisement (`router_stage_advertisement.json`)

Pins the router-programmed stage (sublocation) binding of
`docs/SUBLOCATION_ADVERTISEMENT.md`: the scan-response manufacturer-data
layouts (legacy `[uuid:16]` / v1 `[ver=0x01][stageHash:4][uuid:16]`, company id
`0xFFFF`), the 4-byte `SHA256(stageId)` prefix hash, the phones' strict parse
gates, and the per-flush effective-mapping overlay semantics.

Four groups:

| Group | Normative for |
|---|---|
| `stageHash` (G1), `encode` (G2) | **this repo** — the emitter (`router/BeaconIdentity.kt`, consumed by `RouterStageAdvertisementTest`) |
| `parse` (G3), `effectiveMapping` (G4) | the phone clients (loxation-sw honor logic, loxation-android mirror) |

The load-bearing invariant across every group: `beacon_uuid` is the TRAILING
16 bytes of the payload in every layout version, forever — that is what keeps
every deployed client's suffix-16 membership extraction correct against a
stage-configured router.

Other shared fixtures listed in loxation-sw's `fixtures/README.md`
(`peer_observation.json`, `contact_hash.json`, the DEFLATE blobs, …) predate
this directory and have not been mirrored here yet. The router does not
currently have unit-test coverage pinned to those vectors (it bridges 0x44
observations byte-for-byte and has no contact-hash code; its only compression
path is `CompressionUtil`, untested here) — mirror the relevant blob and add a
parity test when the router grows a codec for one of them.
