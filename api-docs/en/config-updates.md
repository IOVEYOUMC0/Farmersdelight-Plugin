
[简体中文](../zh-cn/config-updates.md)
# Keeping an operator's config.yml up to date

Package: `com.huidu.farmersdelight.api.config`

## The problem this exists for

Bukkit's `saveDefaultConfig()` writes the bundled file **only when none exists**. On a server that has been
running since before your last release, that means:

- every setting you added in a later version is simply absent from the operator's file, forever, and the
  feature behind it silently reads whatever hardcoded fallback your `getConfig().getBoolean(path, default)`
  call carries;
- every path you renamed still sits in the file under its old name, where nothing reads it, and the value the
  operator carefully tuned no longer does anything;
- every setting you retired is still there, looking like it works.

The operator has no way to notice any of this. The only honest fix is for the plugin to reconcile the file
against the one the running build ships, on every startup, without touching a single value the operator set.
That is what this package does.

FarmersDelight bootstraps its own `config.yml` and `gui.yml` with it, and Brewin' And Chewin' — the reference
consumer for addons — runs the whole thing in four lines. This is the newest part of the API.

## The four types

| Type | Shape | Role |
| --- | --- | --- |
| `ConfigFileUpdater` | final class, static methods, private constructor | the machinery; holds no state |
| `ConfigUpdatePolicy` | final class, builder | your data: renames, retired paths, registry sections |
| `ConfigKeyRename` | record `(String oldPath, String newPath)` | one rename |
| `ConfigUpdateReport` | final class | what one update actually did |

None of these carry knowledge of any particular config file. Every plugin supplies its own tables, so one
plugin and its addons can share the behaviour without sharing a voice, a language file or a set of config
tables.

Two design decisions follow from that split and are worth stating outright:

- **The API never logs.** It returns a `ConfigUpdateReport` and the caller says what it wants to say, in its
  own wording and its own language files.
- **A failed backup is reported, not thrown.** It appears on `report.backupError()`. Losing the safety net is
  not a reason to abandon an update that is otherwise correct — but it is something the operator deserves to
  be told about, so log it.

`ConfigUpdateReport`'s constructors are package-private: you receive reports, you never build one.

## The recommended entry point

```java
public static ConfigUpdateReport updateMainConfig(Plugin plugin, ConfigUpdatePolicy policy)
        throws IOException, InvalidConfigurationException;
```

One call performs the whole ordered sequence against your plugin's own `config.yml`:

1. read the bundled `config.yml` from the jar (returns an empty report if the jar has none — a build may
   legitimately not ship one);
2. apply the renames, in the order the policy lists them;
3. delete the retired paths;
4. merge in the missing settings, with their comments;
5. if nothing changed, stop here and return — no write;
6. otherwise take a timestamped backup of the file, pin the YAML dump options, `saveConfig()`, `reloadConfig()`;
7. return the report, carrying `backupError` if the copy could not be written.

Use this rather than the individual steps unless you need to interleave something between them. The steps are
order-dependent, and a caller that saves without pinning the dump options first gets a file whose long values
are folded across several physical lines the first time it is rewritten.

## The reference consumer

This is Brewin' And Chewin's `BrewinConfigBootstrap`, essentially in full:

```java
private static final ConfigUpdatePolicy CONFIG_POLICY = ConfigUpdatePolicy.builder()
        .migrate("tipsy.max-points", "tipsy.max-duration-seconds")
        .registrySection("food-effects.nourishment",
                "food-effects.comfort",
                "booze-effects.drinks",
                "tipsy.amounts",
                "tipsy.amplifiers",
                "tipsy.effects",
                "coaster.display.overrides")
        .build();

public void updateConfig() {
    ConfigUpdateReport report;
    try {
        report = ConfigFileUpdater.updateMainConfig(plugin, CONFIG_POLICY);
    } catch (Exception e) {
        warn("bac.config_update_failed", "file", "config.yml", "error", String.valueOf(e.getMessage()));
        return;
    }

    if (report.backupError() != null) {
        warn("bac.config_backup_failed", "file", "config.yml", "error", report.backupError());
    }
    for (ConfigKeyRename rename : report.migratedKeys()) {
        info("bac.config_key_migrated", "old", rename.oldPath(), "new", rename.newPath());
    }
    if (!report.retiredKeys().isEmpty()) {
        info("bac.config_keys_retired", "count", report.retiredKeys().size(),
                "keys", String.join(", ", report.retiredKeys()));
    }
    if (report.addedKeys() > 0) {
        info("bac.config_keys_merged", "count", report.addedKeys());
    }
}
```

Called from `onEnable`, immediately after `saveDefaultConfig()` and **before anything reads the config**:

```java
saveDefaultConfig();
new BrewinConfigBootstrap(this).updateConfig();
// ... everything that reads getConfig() comes after this line
```

## Why the order is load-bearing

**Renames must run before the merge.** A rename is skipped when the new path is already set — that is
deliberate, so a file an operator updated by hand keeps the value it carries there. But it means that if the
merge runs first, it plants the bundled default at the new path, every rename then becomes a no-op, and the
tuned value sitting under the old name is silently dropped.

Brewin' And Chewin' has exactly this case. An earlier revision kept a points meter with a ceiling at
`tipsy.max-points`; the duration model that replaced it bounds the same quantity under
`tipsy.max-duration-seconds`, so the number the operator tuned still means what it meant and should be carried
over. Merge first, and the operator's tuned ceiling ends up abandoned in the file while the bundled default
takes effect.

**Retired keys are dropped in between** — after a rename has moved a value to its current path, and before
the merge writes anything, so a setting nothing reads is not carried forward again.

`updateMainConfig` gets this right for you. If you build the sequence yourself, keep it in that order.

Renames apply **in the order the policy lists them**, which lets one path chain across two renames in a single
pass: an entry moving `a` to `b` followed by one moving `b` to `c` carries a file still using `a` all the way
to `c`. FarmersDelight's own policy uses this to fold several generations of drop-config names onto one
current path.

A rename recreates a section key by key at the new path. **Comments are not carried across a rename.**

## Registry sections

A *registry section* is a section whose children are **content keyed by id**, not fixed settings — a table of
foods, of drinks, of display overrides. The distinction matters because for content, **deleting an entry is
how an operator disables that item**. Merging entries back one at a time would silently undo every such
removal on the next restart, and the operator would have no idea why the item they turned off came back.

So the merge treats these sections as all-or-nothing: it creates the section when the operator's file does not
have it at all, and once the file has the section, its individual entries are never filled in again.

```java
ConfigUpdatePolicy.builder()
        .registrySection("food-effects.nourishment",
                         "booze-effects.drinks",
                         "tipsy.amounts")
        .build();
```

Membership is itself semantic: in Brewin' And Chewin' an item being present in `tipsy.amounts` is what makes
it a drink at all, so deleting an id there is an operator saying that item no longer makes anyone drunk.

Two consequences to accept knowingly:

- **A genuinely new setting added under a registry section in a later version will not reach an existing
  file.** That is the price of not undoing deletions. FarmersDelight pays it deliberately on parents like
  `buff.comfort` and `buff.nourishment`, where the mixed shape (settings *and* a keyed `foods` table) makes
  guarding at the parent the only option.
- **A list the operator emptied still counts as present**, so a `trades:` key holding an empty list is left
  alone rather than being repopulated.

The suppression decision is taken against a snapshot of the file **as the operator left it**, before anything
is written — otherwise the first entry written into an absent registry section would make that section exist,
which would then suppress all its siblings and leave a half-populated entry behind.

## The policy builder

```java
public static ConfigUpdatePolicy.Builder builder();

Builder migrate(String oldPath, String newPath);   // order-sensitive, chainable
Builder retire(String... paths);                   // null path throws NullPointerException
Builder registrySection(String... paths);          // null path throws NullPointerException
ConfigUpdatePolicy build();
```

Readers: `migrations()` → `List<ConfigKeyRename>`, `retiredKeys()` → `List<String>`, `registrySections()` →
`List<String>`. A policy is immutable once built and holds no per-file state, so one policy can be reused for
as many files as you bootstrap. FarmersDelight feeds the same policy to both `config.yml` and `gui.yml`
precisely so the two files cannot drift apart on what counts as a content registry.

## The report

```java
List<ConfigKeyRename> migratedKeys();  // renames that actually moved something, in applied order
List<String>          retiredKeys();   // retired paths that were present and were deleted, in listed order
int                   addedKeys();     // value keys the merge added; sections are NOT counted
boolean               changed();       // true when any of the above is non-empty / non-zero
String                backupError();   // why the pre-write copy failed, or null
```

`changed()` is the condition for writing the file back — `updateMainConfig` uses it internally, and you need
it if you drive the steps yourself. `addedKeys()` counts only value keys: a new section containing three
settings reports `3`, not `4`.

## Driving the steps yourself

You need this when the file is not your plugin's main config (`gui.yml`, a recipe file), or when you want to
interleave work between the phases. FarmersDelight's own `ConfigBootstrap` does both.

```java
public static ConfigUpdateReport applyTo(ConfigurationSection bundled,
                                         ConfigurationSection existing,
                                         ConfigUpdatePolicy policy);

public static List<ConfigKeyRename> applyMigrations(ConfigurationSection config,
                                                    List<ConfigKeyRename> migrations);
public static List<String> removeKeys(ConfigurationSection config, List<String> paths);
public static int copyMissingKeys(ConfigurationSection bundled, ConfigurationSection existing,
                                  List<String> registrySections);
```

`applyTo` runs all three phases in the correct order and returns the report, but **writes nothing** — neither
argument is saved to disk. You call `tidy` and write it yourself when the report says something changed.

A worked example, the shape FarmersDelight's `ConfigBootstrap.mergeMissingGuiKeys` uses for its secondary
`gui.yml`. Three of these four calls are `throws IOException, InvalidConfigurationException`, and two of the
reads have a documented non-throwing failure mode, so the guards below are not decoration — drop them and the
method neither compiles nor survives a first run on a server that has no `gui.yml`:

```java
private void mergeMissingGuiKeys() {
    Path guiPath = plugin.getDataFolder().toPath().resolve("gui.yml");
    // readYamlFile opens the path directly: on a missing file it throws NoSuchFileException, it does
    // not hand back an empty configuration. Nothing to merge into if the operator has no gui.yml yet.
    if (Files.notExists(guiPath)) {
        return;
    }
    // readBundledYaml returns null — not an empty configuration — when the jar ships no such resource.
    // Passing that straight to copyMissingKeys would NPE.
    YamlConfiguration bundled;
    try {
        bundled = ConfigFileUpdater.readBundledYaml(plugin, "gui.yml");
    } catch (Exception e) {
        getLogger().warning("gui.yml merge failed: " + e.getMessage());
        return;
    }
    if (bundled == null) {
        return;
    }
    try {
        YamlConfiguration existing = ConfigFileUpdater.readYamlFile(guiPath);
        int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, policy.registrySections());
        if (added > 0) {
            backupQuietly(guiPath);   // logs and continues if the copy fails
            ConfigFileUpdater.tidy(existing);
            ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
        }
    } catch (Exception e) {
        getLogger().warning("gui.yml merge failed: " + e.getMessage());
    }
}

private void backupQuietly(Path path) {
    try {
        ConfigFileUpdater.backup(path);
    } catch (IOException e) {
        getLogger().warning("Backup of " + path.getFileName() + " failed: " + e.getMessage());
    }
}
```

Two things to copy from that beyond the guards. FarmersDelight takes the backup **inside** the `added > 0`
branch, so a startup that changes nothing does not litter the data folder with `.bak` files. And it wraps
`backup` in its own swallow-and-log helper rather than letting it reach the outer `catch`, so a failed backup
is reported but does not abandon a merge that is otherwise correct — the same stance `updateMainConfig` takes
when it puts the failure on `report.backupError()` and carries on.

`removeKeys` and the merge both discard or rewrite content the operator wrote, so a caller driving the steps
by hand owns the backup: take a copy before you write.

One subtlety inside `copyMissingKeys` that you inherit and should not try to reimplement: presence is tested
with `contains(path, true)`, which ignores the jar defaults Bukkit attaches to `getConfig()`. Plain
`contains(path)` would report every bundled key as already present and turn the whole merge into a silent
no-op. A bundled key whose value is `null` is skipped, because setting `null` removes the path again — it
would count as added on every startup and rewrite the file forever.

Comments **are** copied along with an added key, and for a section that came into existence because one of its
values was added.

## File-level helpers

```java
public static void tidy(FileConfiguration configuration);
public static YamlConfiguration readBundledYaml(Plugin plugin, String resourcePath)
        throws IOException, InvalidConfigurationException;
public static YamlConfiguration readYamlFile(Path file)
        throws IOException, InvalidConfigurationException;
public static void backup(Path file) throws IOException;
public static boolean needsRestore(Path file);
public static void installBundledResource(Plugin plugin, String resourcePath, Path targetPath,
                                          boolean replace) throws IOException;
public static void writeStringAtomically(Path targetPath, String content, boolean replace)
        throws IOException;
```

**`tidy`** must be called on a configuration right before `save` / `saveToString`. Bukkit's YAML dumper
defaults to an 80-character line width, so any longer value is folded across several physical lines when the
file is rewritten — item id lists, message templates and long descriptions come back as multi-line blocks that
are hard to read and easy to break by hand. `tidy` pins the width to effectively unlimited, the indent to 2
spaces (matching the bundled files) and leaves comment parsing on so header documentation and per-key comments
survive the round trip. Anything that is not a `YamlConfiguration` is left alone.

**`readBundledYaml`** returns `null` when the jar has no such resource — the one outcome that is not an error.
Malformed content is raised so you can report it in your own words.

**`readYamlFile`** loads from disk as UTF-8 without Bukkit's jar-default layer, which is how you read a file
you do not own a `getConfig()` for.

**`backup`** writes `<file>.yyyyMMdd-HHmmss.bak` next to the file. Two backups in the same second collapse
into one file rather than piling up.

**`needsRestore`** is `true` when the file on disk cannot be trusted: it does not parse as YAML, it cannot be
read at all, or it contains the Unicode replacement character — what a file saved in the wrong encoding leaves
behind. This is a separate concern from the update, and `updateMainConfig` does **not** perform it. Run it
before the update if you want the same coverage FarmersDelight has:

```java
if (ConfigFileUpdater.needsRestore(configPath)) {
    ConfigFileUpdater.backup(configPath);
    ConfigFileUpdater.installBundledResource(plugin, "config.yml", configPath, true);
    getLogger().warning("config.yml was unreadable and has been restored from the jar.");
}
```

**`installBundledResource`** writes a jar resource to disk atomically. The resource must decode as UTF-8, and
a `.yml` / `.yaml` resource must parse, so a broken build cannot overwrite a working file with rubbish. With
`replace = false` an existing target is an `IOException` rather than being overwritten — that is the
install-if-missing form.

**`writeStringAtomically`** writes through a temporary file in the same directory and an atomic move, so a
crash or a full disk leaves the previous file intact rather than a half-written one. It falls back to a plain
move on a filesystem that cannot move atomically, and creates parent directories as needed.

## Startup checklist

1. `saveDefaultConfig()`.
2. Optionally `needsRestore` → `backup` + `installBundledResource` for an unreadable file.
3. `ConfigFileUpdater.updateMainConfig(this, policy)` inside a `try`/`catch`.
4. Log `backupError()`, the renames, the retired keys and the added count — in your own words.
5. Only now read any config value.

Threading: this is startup file I/O. Run it on the enable thread, before anything else touches the config.
There is nothing region-threaded about it, and nothing here is safe to run concurrently with your own config
reads.

## Related pages

* [Getting started](getting-started.md)
* [Custom buffs and bossbars](buffs.md)
