# BLE Mesh Router

## Project Goal

Create a BLE mesh router that bridges BLE mesh networks over WiFi. The router runs on Android devices acting as WiFi access points.

## Core Responsibilities

1. **Detect non-local packets**: Identify blemesh packets intended for peers NOT present in the local BLE mesh
2. **Route over WiFi**: Forward those packets to WiFi-connected peer access points (other Android devices running this router)
3. **Deliver incoming packets**: Accept packets arriving over WiFi from remote routers and deliver them to local BLE mesh peers
4. **Gossip protocol**: Participate in the GCS-based gossip sync protocol to maintain consistent state across bridged mesh segments

## Reference Implementation

The BLE mesh implementation is ported from `../loxation-android` which is the working reference. It interoperates with `../loxation-sw` (iOS/macOS).

### Key Source Files (loxation-android)

- `app/src/main/java/com/loxation/loxation/service/BLEMeshService.kt` - Main BLE mesh orchestrator
- `app/src/main/java/com/loxation/loxation/service/BleMeshNoiseHandshakeCoordinator.kt` - Noise XX handshake
- `app/src/main/java/com/loxation/loxation/service/BleMeshFragmentationManager.kt` - MTU fragmentation
- `app/src/main/java/com/loxation/loxation/service/MeshStateStore.kt` - Single-threaded state actor
- `app/src/main/java/com/loxation/loxation/protocol/BitChatProtocol.kt` - Message types & protocol
- `app/src/main/java/com/loxation/loxation/protocol/BinaryProtocol.kt` - Wire format encoding/decoding
- `app/src/main/java/com/loxation/loxation/protocol/BlemeshProtocol.kt` - Relay decision logic
- `app/src/main/java/com/loxation/loxation/data/model/BlemeshPacket.kt` - Packet structure
- `app/src/main/java/com/loxation/loxation/data/model/BlemeshMessage.kt` - Message payload
- `app/src/main/java/com/loxation/loxation/sync/GossipSyncManager.kt` - GCS gossip sync
- `app/src/main/java/com/loxation/loxation/sync/GCSFilter.kt` - Golomb-Coded Set filter
- `BLEMESH+LOXATION_protocol.md` - Complete protocol spec

## Protocol Summary

### Packet Format (14-byte fixed header)

```
Offset  Field           Size    Type
0       Version         1       UInt8 (0x01)
1       Type            1       UInt8 (message type)
2       TTL             1       UInt8 (decremented per hop)
3       Timestamp       8       UInt64 big-endian (ms since epoch)
11      Flags           1       Bit flags
12      PayloadLength   2       UInt16 big-endian
14      SenderID        8       UInt64 big-endian (PeerID)
22      RecipientID*    8       Optional (if FLAG_HAS_RECIPIENT)
30+     Payload         Var     Type-specific data
        Signature*      64      Optional (if FLAG_HAS_SIGNATURE)
```

### Key Message Types

- `0x01` ANNOUNCE, `0x03` LEAVE, `0x04` MESSAGE, `0x05` FRAGMENT
- `0x10` NOISE_HANDSHAKE, `0x12` NOISE_ENCRYPTED, `0x13` NOISE_IDENTITY_ANNOUNCE
- `0x40` LOXATION_ANNOUNCE, `0x44` LOCATION_UPDATE
- `0x60` REQUEST_SYNC (gossip)

### Routing Rules

- Relay decision: `shouldRelay()` checks packet type is relayable AND TTL > 1
- NOISE_ENCRYPTED (0x12) is NOT relayed (end-to-end only)
- Broadcast address: `0xFFFFFFFFFFFFFFFF`
- Relay jitter: random 50-200ms delay before forwarding

### PeerID Derivation

```
PeerID = first 8 bytes of SHA256(noise_static_public_key)
```

## Architecture

```
[Remote BLE Mesh] <--BLE--> [Android Router A] <--WiFi--> [Android Router B] <--BLE--> [Local BLE Mesh]
```

Each router:
- Runs a full BLE mesh stack (advertising + scanning)
- Maintains a table of locally-known BLE mesh peers
- Connects to other routers over WiFi (direct AP connections)
- Forwards packets for unknown/remote peers over the WiFi bridge
- Delivers WiFi-received packets to local BLE mesh peers

## Platform

- Android (Kotlin)
- BLE: Android Bluetooth LE APIs (central + peripheral)
- WiFi transport: Direct socket connections between router APs
- Service UUID: `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5D`
- Characteristic UUID: `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E`
