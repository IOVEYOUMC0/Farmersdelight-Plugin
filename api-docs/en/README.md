# English

This is the reference for the FarmersDelight addon API — the surface a third-party plugin compiles against to extend FarmersDelight from inside the server.

## What this is, and what it is not

This is an **in-process Java API**. Your addon is a Bukkit plugin loaded by the same server JVM as FarmersDelight; you call these methods directly, and they return real Bukkit objects. There is no HTTP service, no REST endpoint, no request or response format, and no authentication step anywhere in this document. "Calling the API" means invoking a Java method.

Everything lives under `com.huidu.farmersdelight.api`. The entry point is the static `FarmersDelightApi` class; from there you reach recipes, blocks, items, buffs, food effects, advancements and the event classes.

## Orientation

FarmersDelight sits on top of CraftEngine. CraftEngine owns the custom items, blocks, furniture, models, glyph fonts and tags; FarmersDelight turns those resources into working server-side gameplay — cooking pots, cutting boards, stoves, skillets, crops, food effects and the recipe GUIs. An addon typically does some mix of three things: it ships its own CraftEngine resources with FarmersDelight behaviors attached to them, it registers recipes and content through this API, and it listens to FarmersDelight's events to react to what players do. The CraftEngine side is configuration, not code — you attach a behavior in YAML, and this API is what you use when YAML is not enough.

Start with [Getting started](getting-started.md) for the dependency wiring and plugin lifecycle, then [FarmersDelightApi](farmersdelight-api.md) for the entry point and the feature-detection rules that let one addon jar run against several FarmersDelight versions.

## The CraftEngine side

This book documents the FarmersDelight plugin's Java API only.

If your addon is mostly content, see CraftEngine's official wiki. A large addon can be built with almost no Java at all; you only need this API when you want behavior CraftEngine cannot express.

## Stability contract

**`com.huidu.farmersdelight.api.**` is name-stable and safe to compile against.** The obfuscated build keeps public and protected members of public classes in that package under their original names:

```
keep public class com.huidu.farmersdelight.api.** {
    public protected *;
}
```

**Everything outside that package is repackaged and renamed, and must not be touched.** If you reach into `com.huidu.farmersdelight.block`, `...util`, `...i18n` or any other internal package, your addon will compile against a development build and then fail at runtime on the released jar with `NoClassDefFoundError` or `NoSuchMethodError`, because those names no longer exist. Note also that only _public and protected_ members of _public_ classes are kept — a package-private class inside the api package, such as `SnapshotItems`, is an implementation detail and is not part of the contract.

Compile against the api-only jar (`gradlew apiJar`) rather than the full plugin jar. That makes the boundary a compile error instead of a production incident. See [Getting started](getting-started.md).

Individual types carry `@ApiStatus` annotations that narrow this further:

* `@ApiStatus.NonExtendable` — call it, do not subclass or implement it.
* `@ApiStatus.OverrideOnly` — implement it, do not call it yourself.
* `@ApiStatus.Experimental` — may change; pin the FarmersDelight version if you depend on it.
* `@ApiStatus.Internal` — not part of the contract despite being public.

Each page states the annotations on the types it covers. Where behaviour depends on the running Minecraft version or on Folia's region threading, the page says so explicitly rather than leaving it to be discovered.
