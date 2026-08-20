package com.huidu.farmersdelight.block.behavior;

// Generic entry point for reading a block behavior's configured block lists from outside the
// behavior (recipe GUI display, docs, ...). Behaviors holding ConfiguredBlockSet config values
// implement this interface and get registered in BlockBehaviorConfigs by their block id.
public interface ConfiguredBlockSetProvider {

    // Returns the configured block set stored under the given config key, or null when unknown.
    ConfiguredBlockSet configuredBlockSet(String key);
}
