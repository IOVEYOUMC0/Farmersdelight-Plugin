package com.huidu.farmersdelight.listener;

/**
 * The knife-harvest decision, kept out of the event wiring so it can be tested without a server.
 */
final class KnifeHarvestRules {

    enum Decision {
        /** Leave the break alone: not a crop the knife harvests. */
        IGNORE,
        /** Stop the break: a knife cannot cut an immature crop. */
        CANCEL,
        /** Let the break drop its loot, then replant the crop at age 0. */
        REPLANT
    }

    private KnifeHarvestRules() {
    }

    static Decision decide(boolean harvestableCrop, boolean unaffected, boolean mature) {
        if (!harvestableCrop || unaffected) {
            return Decision.IGNORE;
        }
        return mature ? Decision.REPLANT : Decision.CANCEL;
    }
}
