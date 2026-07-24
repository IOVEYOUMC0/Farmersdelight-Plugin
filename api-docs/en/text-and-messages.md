# Text and messages

`com.huidu.farmersdelight.api.text` holds two final utility classes. `FarmersDelightText` turns a template
string into an Adventure `Component`; `FarmersDelightMessages` renders a template and sends it to a player in
one call. Both are pure static surfaces with private constructors — you call them, you never extend or
instantiate them. Neither class carries an `@ApiStatus` annotation.

Use them instead of calling MiniMessage yourself. A raw `MiniMessage.deserialize` call will not resolve
FarmersDelight's `<l10n:>` translation tags or CraftEngine's `<image:>` glyph tags, so an addon that
hand-rolls its text ends up showing literal `<image:brewinandchewin:icon_stout>` in chat.

## What a template goes through

`FarmersDelightText.render` applies these steps in order:

1. `{key}` string placeholders are substituted.
2. `<l10n:>` / `<lang:>` translation tags are resolved for the viewer's locale.
3. CraftEngine `<image:ns:id>` and `<shift:N>` glyph tags are resolved to image-font output.
4. MiniMessage and legacy (`&` / `§`) color codes are parsed.

The `viewer` parameter may be null, in which case the default locale is used.

## FarmersDelightText

| Method | Returns | Notes |
| --- | --- | --- |
| `render(String template, Player viewer)` | `Component` | No placeholders. |
| `render(String template, Player viewer, Map<String, String> placeholders)` | `Component` | Substitutes `{key}`. |
| `render(String template, Player viewer, Map<String, String> placeholders, Map<String, Component> components)` | `Component` | Also splices `{key}` Component placeholders. |
| `buildLore(List<String> templates, Player viewer, Map<String, String> placeholders)` | `List<Component>` | One rendered line per template, italic explicitly disabled. |
| `resolveGlyphs(String text)` | `String` | Resolves only `<image:>` / `<shift:N>`; leaves everything else alone. |
| `glyph(String glyphId)` | `Component` | A single CraftEngine image glyph; empty when the id cannot be resolved. |
| `shift(int pixels)` | `String` | A horizontal pixel-shift glyph string for embedding in a template. |
| `serverText(String key)` | `String` | Plain text in the **server's** default locale. |
| `serverComponent(String key, Object... args)` | `Component` | `serverText` formatted with positional `%s` args, wrapped in `Component.text`. |
| `translatable(String key, Object... args)` | `Component` | `Component.translatable` rendered in each **client's** locale, with a server-resolved fallback. |
| `formatDuration(int seconds)` | `String` | Vanilla-style `mm:ss`, or `h:mm:ss` past an hour. Negative input clamps to `0:00`. |

The four-arg `render` overload is what you want when a placeholder is itself formatted text — an item display
name that carries its own glyphs and colors cannot survive being flattened to a `String`:

```java
Map<String, Component> components = Map.of("fluid", itemNameComponent);
Component line = FarmersDelightText.render("<gray>Contains {fluid}", viewer, null, components);
```

`resolveGlyphs` is the escape hatch for code that already owns a MiniMessage pipeline. BAC uses it to inject a
CraftEngine icon into a string it then deserializes itself:

```java
String glyph = FarmersDelightText.resolveGlyphs("<image:brewinandchewin:icon_" + slug + ">");
```

### Choosing between serverText, serverComponent and translatable

This is the distinction that matters most, and getting it wrong produces bugs that only appear for players on
another language or another resource pack.

- **`serverText` / `serverComponent`** resolve entirely on the server, in the server's default locale. Every
  viewer sees identical, pre-rendered text. `serverText` walks FD's lang files, then CraftEngine's
  `TranslationManager`, then Adventure's `GlobalTranslator`, and returns the key itself when nothing has a
  value. Use these whenever the text must **not** depend on the receiving client — broadcasts, or anywhere a
  per-client render would be wrong.
- **`translatable`** produces a `Component.translatable(key, args)` that each client renders in its own
  language, carrying a server-resolved `.fallback(...)` so a client whose resource pack lacks the entry sees
  readable text rather than a raw `namespace.key`. Use it for lore lines and bossbar titles that *should*
  follow each player's language.

Args passed to `translatable` are wrapped in `Component.text` unless they already are Components.
`serverComponent` plain-text-serializes Component args first, so its result is always a flat text Component
with nothing nested inside it.

BAC's keg tooltip is a real `translatable` call — note that the color is applied afterwards, because the lang
value is plain text with no color codes of its own:

```java
Component servingsLine = FarmersDelightText.translatable(
        "tooltip.brewinandchewin.servings_line",
        servings, tank.amountMb(), capacity
).color(NamedTextColor.GRAY);
```

When you embed an item name in a lore line, pair `translatable` with
`FarmersDelightItems.translatableDisplayNameOfNoAnvilOf` rather than the plain display-name accessor — lore
should ignore an anvil rename. See [Items](items.md) for the four display-name renderings and when each
applies.

`formatDuration` exists for buff bossbar titles following the `"<name> [%s]"` convention, and is deliberately
hand-rolled rather than `String.format` because it is called from the PlaceholderAPI hot path. See
[Buffs and bossbars](buffs.md).

## FarmersDelightMessages

Each method renders its template through `FarmersDelightText.render` for the target player, then delivers it.

| Method | Delivery |
| --- | --- |
| `send(Player player, String template)` | Chat |
| `send(Player player, String template, Map<String, String> placeholders)` | Chat |
| `actionBar(Player player, String template)` | Action bar |
| `actionBar(Player player, String template, Map<String, String> placeholders)` | Action bar |
| `title(Player player, String titleTemplate, String subtitleTemplate, int fadeInTicks, int stayTicks, int fadeOutTicks, Map<String, String> placeholders)` | Title and subtitle |

`send` and `actionBar` no-op when either the player or the template is null. `title` only null-checks the
player: either template may be null and renders as an empty line, which is how you show a subtitle with no
title. There is no shorter `title` overload — pass `Map.of()` when you have no placeholders.

The tick durations are converted at 50 ms per tick and clamped at zero, so negative values are treated as zero
rather than throwing.

This is the full set of calls, taken from FDAddonTemplate:

```java
FarmersDelightMessages.send(player, "<green>Hello {name}!", Map.of("name", player.getName()));
FarmersDelightMessages.actionBar(player, "<gold>Saved");
FarmersDelightMessages.title(player, "<aqua>Title", "<gray>Subtitle", 10, 40, 10, Map.of());
```

## Threading

Every `FarmersDelightMessages` method touches the player, so call it on the player's owning thread — the main
thread on Paper, the player's own region thread on Folia. Do not call these from an async task. If you are
holding a result from async work, hop back first; see [Scheduling](scheduling.md) for the region-correct
scheduler entry points.

`FarmersDelightText` only builds a `Component` and delivers nothing, and the source states no threading
requirement for it — UNKNOWN whether its translation and glyph lookups are safe off the main thread. The
`viewer` overloads read that player's locale, so the safe course is to render on the same thread you intend to
send from.

## Related pages

* [Items](items.md)
* [Custom buffs and bossbars](buffs.md)
* [Scheduling](scheduling.md)
