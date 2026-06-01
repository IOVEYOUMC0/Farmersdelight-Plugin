package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuraSkillsHook {

    private static final String PLUGIN_NAME = "AuraSkills";
    private static final String API_CLASS = "dev.aurelium.auraskills.api.AuraSkillsApi";
    private static final String USER_CLASS = "dev.aurelium.auraskills.api.user.SkillsUser";
    private static final String SKILL_CLASS = "dev.aurelium.auraskills.api.skill.Skill";
    private static final String SKILLS_ENUM_CLASS = "dev.aurelium.auraskills.api.skill.Skills";
    private static final String GLOBAL_REGISTRY_CLASS = "dev.aurelium.auraskills.api.registry.GlobalRegistry";
    private static final String NAMESPACED_ID_CLASS = "dev.aurelium.auraskills.api.registry.NamespacedId";

    private final FarmersDelightPlugin plugin;
    private final Map<String, Object> skillCache = new ConcurrentHashMap<>();
    private final Set<String> missingSkills = ConcurrentHashMap.newKeySet();

    private boolean initialized;
    private boolean available;
    private Object api;
    private Class<?> skillsEnumClass;
    private Method getUser;
    private Method getGlobalRegistry;
    private Method namespacedIdOf;
    private Method registryGetSkill;
    private Method addSkillXp;
    private Method addSkillXpRaw;
    private Method userIsLoaded;
    private Method skillIsEnabled;

    public AuraSkillsHook(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void addXp(Player player, String skill, double amount, boolean raw) {
        addXp(player, new XpDefinition(skill, amount, raw));
    }

    public void addXp(Player player, XpDefinition definition) {
        if (player == null || definition == null || definition.amount() <= 0.0D || !ensureAvailable()) {
            return;
        }

        try {
            Object skill = resolveSkill(definition.skill());
            if (skill == null || !isSkillEnabled(skill)) {
                return;
            }

            Object user = getUser.invoke(api, player.getUniqueId());
            if (user == null || !isUserLoaded(user)) {
                return;
            }

            Method xpMethod = definition.raw() ? addSkillXpRaw : addSkillXp;
            xpMethod.invoke(user, skill, definition.amount());
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().fine(I18n.formatConsole("plugin.auraskills_xp_failed",
                    "skill", definition.skill(),
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private boolean ensureAvailable() {
        if (initialized) {
            return available && Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
        }

        initialized = true;
        Plugin auraSkills = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (auraSkills == null || !auraSkills.isEnabled()) {
            available = false;
            return false;
        }

        try {
            ClassLoader loader = auraSkills.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(API_CLASS, true, loader);
            Class<?> userClass = Class.forName(USER_CLASS, true, loader);
            Class<?> skillClass = Class.forName(SKILL_CLASS, true, loader);
            Class<?> globalRegistryClass = Class.forName(GLOBAL_REGISTRY_CLASS, true, loader);
            Class<?> namespacedIdClass = Class.forName(NAMESPACED_ID_CLASS, true, loader);
            skillsEnumClass = Class.forName(SKILLS_ENUM_CLASS, true, loader);

            api = apiClass.getMethod("get").invoke(null);
            getUser = apiClass.getMethod("getUser", UUID.class);
            getGlobalRegistry = apiClass.getMethod("getGlobalRegistry");
            namespacedIdOf = namespacedIdClass.getMethod("of", String.class, String.class);
            registryGetSkill = globalRegistryClass.getMethod("getSkill", namespacedIdClass);
            addSkillXp = userClass.getMethod("addSkillXp", skillClass, double.class);
            addSkillXpRaw = userClass.getMethod("addSkillXpRaw", skillClass, double.class);
            userIsLoaded = findOptionalMethod(userClass, "isLoaded");
            skillIsEnabled = findOptionalMethod(skillClass, "isEnabled");
            available = api != null;
            return available;
        } catch (ReflectiveOperationException | RuntimeException e) {
            available = false;
            I18n.logWarning("plugin.auraskills_hook_failed", "error", e.getMessage());
            return false;
        }
    }

    private Method findOptionalMethod(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Object resolveSkill(String skillId) throws ReflectiveOperationException {
        String normalized = normalizeSkillId(skillId);
        if (normalized.isEmpty()) {
            return null;
        }
        if (missingSkills.contains(normalized)) {
            return null;
        }

        Object cached = skillCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        Object skill = resolveDefaultSkill(normalized);
        if (skill == null) {
            skill = resolveRegisteredSkill(normalized);
        }

        if (skill == null) {
            if (missingSkills.add(normalized)) {
                I18n.logWarning("plugin.auraskills_skill_missing", "skill", normalized);
            }
            return null;
        }

        skillCache.put(normalized, skill);
        return skill;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object resolveDefaultSkill(String skillId) {
        if (skillsEnumClass == null || (skillId.contains("/") && !skillId.startsWith("auraskills/"))) {
            return null;
        }

        String enumName = skillId;
        int separator = enumName.indexOf('/');
        if (separator >= 0) {
            enumName = enumName.substring(separator + 1);
        }
        enumName = enumName.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');

        try {
            return Enum.valueOf((Class<? extends Enum>) skillsEnumClass.asSubclass(Enum.class), enumName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Object resolveRegisteredSkill(String skillId) throws ReflectiveOperationException {
        String namespace = "auraskills";
        String name = skillId;
        int separator = skillId.indexOf('/');
        if (separator >= 0) {
            namespace = skillId.substring(0, separator);
            name = skillId.substring(separator + 1);
        }
        if (namespace.isBlank() || name.isBlank()) {
            return null;
        }

        Object namespacedId = namespacedIdOf.invoke(null, namespace, name);
        Object registry = getGlobalRegistry.invoke(api);
        Object result = registryGetSkill.invoke(registry, namespacedId);
        if (result instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return result;
    }

    private boolean isUserLoaded(Object user) throws ReflectiveOperationException {
        if (userIsLoaded == null) {
            return true;
        }
        Object loaded = userIsLoaded.invoke(user);
        return !(loaded instanceof Boolean bool) || bool;
    }

    private boolean isSkillEnabled(Object skill) throws ReflectiveOperationException {
        if (skillIsEnabled == null) {
            return true;
        }
        Object enabled = skillIsEnabled.invoke(skill);
        return !(enabled instanceof Boolean bool) || bool;
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null) {
            return "";
        }
        return skillId.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace(':', '/');
    }

    public record XpDefinition(String skill, double amount, boolean raw) {
    }
}
