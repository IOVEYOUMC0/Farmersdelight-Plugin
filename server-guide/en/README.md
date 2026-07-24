---
icon: rocket
---

# Server Owner Guide

This is the friendly getting-started flow for running **FarmersDelight** on your server: what you need, how to
install it, how to confirm it worked, the first knobs most owners touch, and how to read the console when
something is off.

It is deliberately short and points you into the **[Admin Wiki](/)** for the full reference. The Admin Wiki
already documents every `config.yml` section, every block definition, the effects, the commands and the
migration reference. This guide does **not** repeat that — it gets you from a downloaded jar to a working
server, and hands you off to the Admin Wiki once you are running.

## Read in this order

1. **[Requirements & load order](requirements.md)** — Paper/Folia and Java versions, and why CraftEngine has
   to load first.
2. **[Installation](install.md)** — place the jar, first start, `/ce reload all`, and confirm the blocks and
   items exist.
3. **[Resource pack](resource-pack.md)** — how CraftEngine serves the pack, and the two failure modes you will
   actually hit.
4. **[First config tweaks](first-config.md)** — the handful of `config.yml` settings most owners change on day
   one, with links into the Admin Wiki for the details.
5. **[Verifying behaviours loaded](verifying.md)** — how to read a CraftEngine behavior error, which is a
   signal that a block's config is wrong, not a crash.
6. **[Troubleshooting](troubleshooting.md)** — symptom → cause → fix, plus where the logs are.
7. **[Migration & upgrades](migration.md)** — what carries over from a fresh install or an update, and why some
   shipped files are never overwritten.

## The 60-second version

- Drop **CraftEngine** and **FarmersDelight** into `plugins/`. CraftEngine is a hard dependency and loads
  first automatically.
- Start the server once. FarmersDelight extracts its CraftEngine resources to
  `plugins/CraftEngine/resources/farmersdelight/` and its own `config.yml` to `plugins/FarmersDelight/`.
- Generate and host the resource pack the way CraftEngine documents, then run `/ce reload all`.
- Give yourself a `farmersdelight:cooking_pot` to confirm items resolve, place it, and open it.

If that all worked, you are done — jump to [First config tweaks](first-config.md). If not,
[Troubleshooting](troubleshooting.md) has you covered.
