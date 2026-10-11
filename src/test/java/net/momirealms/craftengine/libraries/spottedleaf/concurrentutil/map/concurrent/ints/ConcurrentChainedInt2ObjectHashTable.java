package net.momirealms.craftengine.libraries.spottedleaf.concurrentutil.map.concurrent.ints;

/** Type-only fixture for NetWorkUser.entityViews(); packet-culling tests must never call that method. */
public final class ConcurrentChainedInt2ObjectHashTable<V> {
    private ConcurrentChainedInt2ObjectHashTable() {
        throw new AssertionError("CE entityViews is outside the proxy-display test fixture");
    }
}
