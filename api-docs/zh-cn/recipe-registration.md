---
icon: fire-burner
---

# 厨锅与砧板配方

这是配方 API 的另一半：不是渲染你自己工作站的配方，而是往 **FarmersDelight 的**工作站里加配方。这样注册的配方会被 真正的厨锅煮、被真正的砧板切，会出现在 FarmersDelight 自己的配方界面里，也会参与配方发现。

注册接口在 `FarmersDelightApi` 上，只读查询在 `com.huidu.farmersdelight.api.recipe.FarmersDelightRecipes` 上。

## 原料表达式语法

原料是字符串，语法和配方文件里的完全一致：

| 写法        | 含义                            |
| --------- | ----------------------------- |
| `ns:id`   | 就是这个物品（原版或 CraftEngine 自定义物品） |
| `#ns:tag` | 该标签内的任意物品                     |
| `a\|b`    | 或选 —— 任意一项满足即可，每一项本身可以是物品或标签  |

## 注册厨锅配方

```java
public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                     ItemStack result, double experience, int cookTime, String category);

public void unregisterCookingPotRecipe(String id);
```

* `container` 是需要的碗 / 瓶；`null`（或空气 stack）表示不需要。
* `result` 自带数量。
* `container` 和 `result` 在进入时会被克隆，你仍然持有自己那份 stack 的所有权。
* `experience` 钳到 `>= 0`，`cookTime` 钳到 `>= 1`，`category` 为 `null` 时变成 `"misc"`。
* 用已存在的 id 注册会**替换**掉旧的。
* 配方能挺过 `/fd reload`，但挺不过重启 —— 每次启用都要重新注册。

失败有两种模式，区别很重要：

* 插件为 null、FarmersDelight 不可用，或 `id` / `ingredients` / `result` 为 `null` —— **静默空操作**。
* `id` 为空白、`ingredients` 为空列表、`result` 是空气 —— **抛 `IllegalArgumentException`**。

所以由配置驱动的注册器应当先自行校验，而不是指望它静默跳过。

重新发布是**合并到下一 tick** 的：一批注册只会在一 tick 之后触发一次配方重载。紧跟在 `registerCookingPotRecipe` 之后发起的查询还看不到新配方。

## 注册砧板配方

```java
public void registerCuttingBoardRecipe(String id, String input, String tool,
                                       List<ItemStack> results, String sound);

public void unregisterCuttingBoardRecipe(String id);
```

* `input` 和 `tool` 用同一套原料语法。**`tool` 是必填的** —— 通过这个 API 无法注册「任意工具」的配方。
* `results` 里每个 stack 自带数量，且都是 100% 掉落。配方文件支持的概率性多产物在这里没有暴露。
* `sound` 是音效 id，`null` 表示默认的小刀音效。
* `results` 里的 `null` 和空气 stack 会在校验之前被剔除。
* `id` / `input` / `tool` / `results` 为 `null` 时静默空操作；`id` 空白、`input` 空白、`tool` 空白、`input` 表达式 解析不出展示物品，或剔除后 `results` 里没有任何有效项时，抛 `IllegalArgumentException`。
* 与厨锅相同的下一 tick 重新发布，相同的 `/fd reload` 保留策略。

## 什么时候注册

结果和容器是 `ItemStack`，所以 CraftEngine 物品必须已经加载完。FarmersDelight 自己也是把配方加载推迟到 `CraftEngineReloadEvent` 的，你照做，并在之后每次 CE 重载时重新注册。下面是 BAC 的注册器，做了精简：

```java
public final class BrewinCookingPotRecipes implements Listener {

    private final JavaPlugin plugin;
    private final Set<String> registeredIds = new LinkedHashSet<>();

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        BrewinItems.clearCache();
        register();
    }

    public void register() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "recipes/cooking_pot_recipes.yml"));
        ConfigurationSection root = config.getConfigurationSection("cooking_pot_recipes");
        if (root == null) {
            return;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> freshIds = new LinkedHashSet<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            List<String> ingredients = section.getStringList("ingredients");
            String resultId = section.getString("result");
            if (ingredients.isEmpty() || resultId == null) {
                continue;
            }
            ItemStack result = BrewinItems.create(resultId);
            if (result == null) {
                continue; // CraftEngine items not ready yet; a later CraftEngineReloadEvent retries.
            }
            result.setAmount(Math.max(1, section.getInt("result-count", 1)));
            String containerId = section.getString("container");
            ItemStack container = containerId == null ? null : BrewinItems.create(containerId);
            String recipeId = "brewinandchewin:" + key;
            api.registerCookingPotRecipe(recipeId, ingredients, container, result,
                    section.getDouble("experience", 0.0), section.getInt("cook-time", 200),
                    section.getString("category", "misc"));
            freshIds.add(recipeId);
        }
        // Drop recipes that were registered before but are gone now, then adopt the fresh set.
        for (String stale : registeredIds) {
            if (!freshIds.contains(stale)) {
                api.unregisterCookingPotRecipe(stale);
            }
        }
        registeredIds.clear();
        registeredIds.addAll(freshIds);
    }

    public void unregister() {
        FarmersDelightApi api = FarmersDelightApi.get();
        for (String id : registeredIds) {
            api.unregisterCookingPotRecipe(id);
        }
        registeredIds.clear();
    }
}
```

这个写法比朴素写法多做对了三件事：它是**幂等的**（`onEnable` 和启动时的 `CraftEngineReloadEvent` 都可能调到它）； 它会**跳过**那些 CraftEngine 物品还没加载好的条目，留给后续重载重试；它还会**反注册**配置里已经消失的 id，而不是 在锅里留下一堆孤儿配方。

## 查询配方

`FarmersDelightRecipes` 是 `@ApiStatus.NonExtendable` 的 final 纯静态类。FarmersDelight 未**加载**时，每个方法都会 退化成安全的空结果——但这句话里的「加载」是有分量的，它跟「已启用」不是一回事。

这里每个方法都只对 `FarmersDelightPlugin.getInstance()` 判空，然后就去调 `plugin.getCookingPotRecipes()` 或 `plugin.getCuttingBoardRecipes()`。这两个访问器在各自的 manager 字段为 `null` 时抛 `IllegalStateException("Plugin is not enabled")`——而这些字段是在 `onEnable` 里赋值、在 `onDisable` 里置回 `null` 的，实例却是在 `onLoad` 里就赋好的。于是：在 FarmersDelight 的 `onLoad` 之后、配方管理器构建之前，以及 它 `onDisable` 之后，这些查询**是抛异常，不是返回空结果**。同一层的其它门面并不一样： `FarmersDelightKnifeDrops` 取 handler 时走的是一道会查 `isEnabled0()` 并返回 `null` 的守卫，所以它是真的退化； `FarmersDelightRecipes` 没有这道守卫。

查询前先用 `FarmersDelightApi.get().isAvailable()` 把门（它查的是 enabled 标志，而这个标志是 `onDisable` 的第一 条语句就清掉的），附属就永远碰不到这个异常。另外要分清：`FarmersDelightApi` 上的**注册**方法 （`registerCookingPotRecipe`、`unregisterCookingPotRecipe`、`registerCuttingBoardRecipe`、 `unregisterCuttingBoardRecipe`）内部已经调了 `isAvailable()`，它们确实是静默空操作；漏查的只有 `FarmersDelightRecipes` 这套查询门面。`FarmersDelightApi` 的调度类方法是同一个陷阱，见调度页。

```java
public static boolean matchesCookingPot(List<ItemStack> inputs, ItemStack container);
public static ItemStack cookingPotResult(List<ItemStack> inputs, ItemStack container);
public static boolean hasCuttingBoardRecipe(ItemStack input);
public static List<ItemStack> cuttingBoardResults(ItemStack input, ItemStack tool);

public static List<String> cookingPotRecipeIds();
public static RecipeInfo cookingPotRecipe(String id);
public static List<String> cuttingBoardRecipeIds();
public static RecipeInfo cuttingBoardRecipe(String id);
```

`cookingPotResult` 和 `cuttingBoardResults` 返回的是**克隆**。这是有意的：底层配方只持有一份活的结果 stack，直接 交出去的话，调用方一个 `setAmount` 就能污染这条配方今后的每一次烹饪。既然是克隆，你随便改都没事。

`cookingPotRecipeIds()` 列的是默认配方表 —— 内置的加上附属注册的。只存在于某个自定义配方**组**里的配方不在其中。

```java
for (String id : FarmersDelightRecipes.cookingPotRecipeIds()) {
    RecipeInfo info = FarmersDelightRecipes.cookingPotRecipe(id);
    if (info != null) {
        getLogger().fine(id + " ingredients=" + info.ingredients() + " results=" + info.results()
                + " time=" + info.cookTimeTicks() + " xp=" + info.experience());
    }
}
```

## RecipeInfo

```java
public record RecipeInfo(String id, String type, List<String> ingredients, List<String> tools,
                         ItemStack container, List<ItemStack> results, int cookTimeTicks,
                         double experience, String category) {

    public static final String TYPE_COOKING_POT = "cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "cutting_board";
}
```

一份只读快照，只携带 Bukkit 和 java 类型，因此能穿过混淆稳定的 API 边界。`ingredients` 和 `tools` 回来的是上文那套 配方文件语法的 id 字符串 —— 内部的原料记录永远不会离开插件。`container()` 和 `results()` 每次访问都克隆，列表字段 是不可变副本，所以你对 `RecipeInfo` 做的任何事都碰不到活配方。

哪些字段有值取决于 `type()`：

| 字段              | `cooking_pot` | `cutting_board` |
| --------------- | ------------- | --------------- |
| `ingredients`   | 全部原料表达式       | 一条：输入表达式        |
| `tools`         | 空             | 工具标签，始终带前导 `#`  |
| `container`     | 需要的碗，或 null   | 恒为 null         |
| `results`       | 一个结果          | 一个或多个结果         |
| `cookTimeTicks` | 烹饪时间          | 恒为 0            |
| `experience`    | 经验            | 恒为 0.0          |
| `category`      | 分类            | 恒为 null         |

注意：`RecipeInfo.TYPE_COOKING_POT` 是不带命名空间的 `"cooking_pot"`，而同一个工作站在配方发现里的类型 id 是 `FarmersDelightRecipeDiscovery.TYPE_COOKING_POT` 上的 `"farmersdelight:cooking_pot"`。这是两个用途不同的常量， 不要互相替代。
