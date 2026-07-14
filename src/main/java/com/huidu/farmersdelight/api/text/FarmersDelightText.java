package com.huidu.farmersdelight.api.text;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.PresentationUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stable, addon-facing rich-text renderer. A template string is turned into a Component by: substituting
 * {key} string placeholders, resolving <l10n:>/<lang:> translation tags for the
 * viewer's locale, resolving CraftEngine <image:ns:id> and <shift:N> glyph tags, then parsing
 * MiniMessage and legacy (&/§) color codes. Component placeholders (e.g. an item display name
 * that itself contains glyphs/colors) can be spliced in with the four-arg overload.
 *
 * Lives in the name-stable api package; uses only Bukkit / Adventure / java types. viewer may
 * be null (uses the default locale).
 */
public final class FarmersDelightText {

    private FarmersDelightText() {
    }

    /** Renders template for viewer (no placeholders). */
    public static Component render(String template, Player viewer) {
        return PresentationUtils.render(template, viewer, null, null);
    }

    /** Renders template for viewer, substituting {key} string placeholders. */
    public static Component render(String template, Player viewer, Map<String, String> placeholders) {
        return PresentationUtils.render(template, viewer, placeholders, null);
    }

    /**
     * Renders template for viewer, substituting {key} string placeholders and splicing
     * {key} Component placeholders (e.g. components.put("fluid", itemNameComponent)).
     */
    public static Component render(String template, Player viewer,
                                   Map<String, String> placeholders, Map<String, Component> components) {
        return PresentationUtils.render(template, viewer, placeholders, components);
    }

    /** Builds non-italic lore lines, one rendered Component per template line. */
    public static List<Component> buildLore(List<String> templates, Player viewer, Map<String, String> placeholders) {
        List<Component> lore = new ArrayList<>();
        if (templates != null) {
            for (String template : templates) {
                lore.add(render(template, viewer, placeholders).decoration(TextDecoration.ITALIC, false));
            }
        }
        return lore;
    }

    /** Resolves only CraftEngine glyph tags (<image:ns:id> / <shift:N>) to image-font output. */
    public static String resolveGlyphs(String text) {
        return PresentationUtils.resolveGlyphTags(text);
    }

    /** A single CraftEngine image glyph as a Component (empty when the id can't be resolved). */
    public static Component glyph(String glyphId) {
        return Text.deserialize(PresentationUtils.imageGlyph(glyphId));
    }

    /** A horizontal pixel-shift glyph string (CraftEngine offset font) for embedding in a template. */
    public static String shift(int pixels) {
        return PresentationUtils.shift(pixels);
    }

    /**
     * Plain text for key resolved entirely on the server in the server's default locale. Walks FD
     * lang files, then CraftEngine's TranslationManager, then Adventure's GlobalTranslator; returns the key
     * itself when no source has a value. Use this whenever the rendered text must NOT depend on the
     * receiving client's locale or resource-pack contents (e.g. lore lines, bossbar titles, broadcasts).
     */
    public static String serverText(String key) {
        return I18n.serverText(key);
    }

    /**
     * serverText(String) formatted with positional %s args and wrapped in a
     * Component.text — pre-rendered, locale-stable, identical for every viewer. Component args
     * get plain-text serialized first; the result is a flat text Component (no nested translatable).
     */
    public static Component serverComponent(String key, Object... args) {
        return I18n.serverComponent(key, args);
    }

    /**
     * A Component.translatable(key, args) that each viewer's client renders in its own locale,
     * with a server-resolved .fallback(...) so clients whose resource pack lacks the lang entry
     * still see readable text (server default locale) instead of the raw key. Args are wrapped in
     * Component.text unless they're already Components.
     *
     * <p>Use for bossbar titles / lore lines where the visible text SHOULD follow the receiving client's
     * language (each player sees their own) but must NEVER show a raw namespace.key on packs
     * without the entry. Pair with FarmersDelightItems#translatableDisplayNameOfNoAnvilOf
     * when embedding an item name in lore (lore should ignore anvil rename).
     */
    public static Component translatable(String key, Object... args) {
        return I18n.translatableWithFallback(key, args);
    }

    /**
     * Vanilla-style mm:ss (or h:mm:ss past an hour) string for a non-negative second
     * count — drop-in arg for buff bossbar titles that follow the "<name> [%s]" convention.
     * Negative input clamps to 0:00.
     *
     * <p>Hand-rolled StringBuilder (not String.format) because this gets called from the PAPI
     * hot path (HUDs × players × placeholder count per tick) — printf-style formatting allocates a
     * Formatter + intermediate StringBuilder + boxes the args each call.
     */
    public static String formatDuration(int seconds) {
        if (seconds < 0) seconds = 0;
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        StringBuilder sb = new StringBuilder(8);
        if (hours > 0) {
            sb.append(hours).append(':');
            if (minutes < 10) sb.append('0');
            sb.append(minutes);
        } else {
            sb.append(minutes);
        }
        sb.append(':');
        if (secs < 10) sb.append('0');
        sb.append(secs);
        return sb.toString();
    }
}
