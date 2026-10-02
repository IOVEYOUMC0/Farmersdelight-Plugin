package com.huidu.farmersdelight.manager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reserves one effect's packets against a chunk's per-tick allowance.
 *
 * <p>A get() < limit check followed by an increment cannot do this for an effect that costs more
 * than one packet: with a single packet left, a two-packet effect (smoke + flame) still passed the check and
 * pushed the chunk one packet over its configured limit. The check and the reservation have to happen
 * together, and an effect that does not fit is skipped rather than partially sent.
 */
final class EffectPacketBudget {

    private EffectPacketBudget() {
    }

    /**
     * @param used  the chunk's counter, or null when the chunk has no viewers to send to
     * @param limit the configured packets-per-tick allowance
     * @param cost  how many packets the effect needs
     * @return true when the reservation was made, false when the effect must be skipped
     */
    static boolean tryReserve(AtomicInteger used, int limit, int cost) {
        if (used == null || cost < 1) {
            return false;
        }
        int current;
        do {
            current = used.get();
            if (cost > limit || current > limit - cost) {
                return false;
            }
        } while (!used.compareAndSet(current, current + cost));
        return true;
    }
}
