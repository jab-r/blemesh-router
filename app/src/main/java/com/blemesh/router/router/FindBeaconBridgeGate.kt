package com.blemesh.router.router

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.PeerID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-sender rate limit + newest-wins coalescer for find beacons crossing onto
 * the WiFi backbone (FIND_MODE_SPEC.md §6: "rate-limited per sender (≥30 s),
 * newest-per-sender coalescing at the bridge").
 *
 * Find mode is the first broadcast type allowed across the backbone that a
 * sender emits on a *fast* cadence: 10–15 s while moving and ~5 s while
 * FIND_REQ-boosted (spec §3). Announces, the other crossing broadcasts, sit at
 * ~30 s and need every crossing for home-router learning. Letting beacons cross
 * unfiltered would put the boosted cadence of every finding peer in the venue
 * onto the shared backbone — the exact aggregate-scales-with-the-crowd failure
 * REGION_LOCAL_ROUTING_SPEC.md §1 exists to prevent.
 *
 * ## Coalesce, don't drop
 *
 * The obvious implementation — drop anything inside the cooldown — satisfies
 * "≥30 s" but wastes most of the window: it forwards whichever beacon happens
 * to arrive first *after* the cooldown expires, which for a stationary sender
 * (45 s keep-current cadence, spec §3) can be another 45 s stale by the time it
 * is picked. So a beacon arriving inside the cooldown is HELD instead, newest
 * timestamp winning, and [drain] releases it once the cooldown expires. Same
 * one-per-30 s backbone cost; the far region gets the freshest fix this router
 * ever saw rather than an arbitrary one.
 *
 * Holding is safe because it gates the BACKBONE leg only. The beacon has
 * already been flooded into the local BLE cell by the mesh relay and stored for
 * local gossip before this gate is consulted, so a coalesced-away beacon is
 * never lost — only its cross-venue copy is deferred, and its own fixAge keeps
 * that deferral honest at the receiver.
 *
 * ## Time
 *
 * Cooldowns are measured on the caller's clock (`System.currentTimeMillis()`
 * passed in as `nowMs`), never on packet timestamps: a sender with a skewed
 * clock must not be able to buy itself a higher backbone share. Packet
 * timestamps are used only to order two beacons from the SAME sender, where
 * skew is common to both.
 *
 * Thread-safe: [offer] and [drain] run from transport read threads and the
 * sweep coroutine concurrently. All read-modify-write on a sender's slot
 * happens inside a `compute`/`computeIfPresent` block (the map's per-key lock),
 * so a beacon can never be dropped between a lookup and a write.
 */
class FindBeaconBridgeGate(private val minIntervalMs: Long) {

    /**
     * [lastCrossedMs] is when this sender's last beacon was released onto the
     * backbone; [held] is the newest beacon received since, awaiting release.
     */
    private class Slot(var lastCrossedMs: Long, var held: BlemeshPacket?)

    private val slots = ConcurrentHashMap<PeerID, Slot>()

    /** Live sender slots — observability for tests and the router snapshot. */
    val size: Int get() = slots.size

    /** Beacons currently held for a later release. */
    val heldCount: Int get() = slots.count { it.value.held != null }

    /**
     * Offer a beacon from [sender] for backbone crossing at [nowMs]. Returns
     * true when the caller should cross it NOW; false when it was coalesced
     * into the sender's slot and will be released by a later [drain].
     */
    fun offer(sender: PeerID, packet: BlemeshPacket, nowMs: Long): Boolean {
        var cross = false
        slots.compute(sender) { _, existing ->
            if (existing == null) {
                // First beacon from this sender: cross immediately. A sender is
                // only "new" here after a full quiet cooldown (drain reaps idle
                // slots), so this cannot be used to bypass the limit by churn.
                cross = true
                Slot(nowMs, null)
            } else if (nowMs - existing.lastCrossedMs >= minIntervalMs) {
                cross = true
                existing.lastCrossedMs = nowMs
                // Anything held is now stale relative to this packet and is
                // superseded — we are crossing the newer one in its place.
                existing.held = null
                existing
            } else {
                val held = existing.held
                // Monotonic: a reordered or replayed older beacon never
                // displaces a fresher held one, matching the receiver's
                // fresher-only merge.
                if (held == null || packet.timestamp >= held.timestamp) {
                    existing.held = packet
                }
                existing
            }
        }
        return cross
    }

    /**
     * Release every held beacon whose sender's cooldown has expired at
     * [nowMs], and reap slots that are idle (no held beacon, cooldown long
     * since expired) so the map stays bounded by ACTIVE senders rather than by
     * every sender ever seen. Reaping an expired-cooldown slot is semantically
     * free: a sender's next beacon would have crossed immediately anyway.
     *
     * Returns the beacons to cross, in no particular order.
     */
    fun drain(nowMs: Long): List<BlemeshPacket> {
        val released = mutableListOf<BlemeshPacket>()
        for (sender in slots.keys) {
            slots.computeIfPresent(sender) { _, slot ->
                if (nowMs - slot.lastCrossedMs < minIntervalMs) return@computeIfPresent slot
                val held = slot.held
                if (held == null) {
                    // Idle past its cooldown — drop the slot entirely.
                    null
                } else {
                    released.add(held)
                    slot.held = null
                    slot.lastCrossedMs = nowMs
                    slot
                }
            }
        }
        return released
    }

    fun clear() {
        slots.clear()
    }
}
