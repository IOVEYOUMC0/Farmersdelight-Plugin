---
icon: list
---

# 配方包总览

`com.huidu.farmersdelight.api.recipe` 是附属 API 里最大的一块。它其实同时承担三件容易混淆的事：

1. **往 FarmersDelight 自己的工作站里加配方** —— 让你的菜真的能被厨锅煮出来、你的物品真的能在砧板上切。见 [厨锅与砧板配方](recipe-registration.md)。
2. **把你自己的配方渲染成配方书** —— 你的附属有自己的工作站（酒桶、搅乳桶、压榨机）和自己的配方格式，希望由 FarmersDelight 负责画界面。见 [RecipeType](recipe-type.md)、[RecipeBookLayout](recipe-book-layout.md)、 [RecipeFiller 与 IngredientMatching](recipe-filler-matching.md)。
3. **让管理员在游戏里编辑这些配方** —— 见 [RecipeEditor](recipe-editor.md)。

以上所有配方的逐玩家解锁状态，统一由[配方发现](recipe-discovery.md)管理。

## 包内清单

| 类型                              | 形态                                 | 你要做的事                   |
| ------------------------------- | ---------------------------------- | ----------------------- |
| `RecipeType`                    | 接口，`@ApiStatus.OverrideOnly`       | 实现它                     |
| `ViewableRecipe`                | 接口，`@ApiStatus.OverrideOnly`       | 实现它                     |
| `RecipeBookLayout`              | 接口，`@ApiStatus.OverrideOnly`       | 实现它（一个 `record` 就够）     |
| `RecipeEditor`                  | 接口，`@ApiStatus.OverrideOnly`       | 实现它                     |
| `RecipeFiller`                  | 接口，`@ApiStatus.OverrideOnly`       | 实现它                     |
| `EditableRecipe`                | final 类                            | 构造并读写                   |
| `NumericField`                  | record                             | 构造                      |
| `JumpTarget`                    | record                             | 构造                      |
| `RecipeInfo`                    | record                             | 接收                      |
| `IngredientMatching`            | final 工具类                          | 调用 `matchesIngredients` |
| `FarmersDelightRecipes`         | final 类，`@ApiStatus.NonExtendable` | 调静态方法                   |
| `FarmersDelightRecipeDiscovery` | final 类，纯静态                        | 调静态方法                   |

五个接口上的 `@ApiStatus.OverrideOnly` 的含义是：这些方法由 FarmersDelight 来调，不是给你调的。你只负责实现并把 实例交给 API，不要去调别的附属的 `RecipeType.recipes()`；也不要假设接口不会新增 `default` 方法 —— 它可能会加， 而正因为是 default，你已有的实现仍然编得过。

`FarmersDelightRecipes` 和 `FarmersDelightRecipeDiscovery` 都是 `final` + 私有构造 + 纯静态成员。除了那五个接口， 这个包里没有任何东西是给附属继承的。

## 接入前的守门

所有入口都走 `FarmersDelightApi.get()`，先守门：

```java
FarmersDelightApi api = FarmersDelightApi.get();
if (!api.isAvailable()) {
    getLogger().warning("FarmersDelight not available; addon features disabled.");
    return;
}
if (!api.hasFeature("recipes")) {
    return;
}
```

`isAvailable()` 只有在 FarmersDelight 既存在又已启用时才为 true。`hasFeature("recipes")` 这个 feature id 覆盖 `registerRecipeType`、`registerCookingPotRecipe`、`registerCuttingBoardRecipe`、`openRecipeBook` 和 `openRecipeEditor`。feature id 一旦发布就不会被删，所以在老版本上探测新 id 只会得到 `false`，是安全的。

在早于 `hasFeature` / `apiVersion` 的构建上，调用本身会抛 `NoSuchMethodError`。如果你要兼容那些构建，首次探测时 捕获它并当作 revision 0 处理。

## 线程

FarmersDelight 支持 Folia，线程归属是硬约束。

* **注册类调用**（`registerRecipeType`、`unregisterRecipeType`、`registerCookingPotRecipe`、 `registerCuttingBoardRecipe` 及各自的 unregister）底层是并发 / 同步 map，任意线程调用都安全。
* **开界面**（`openRecipeBook`、`openRecipeEditor`）最终会走到 `Player.openInventory`，必须在持有该玩家的线程上 调用 —— Folia 上就是玩家所在的区域线程。从你自己针对同一玩家的点击 / 交互处理里调用，本身就已经是对的。
* **你实现的那些接口方法**由 FarmersDelight 在书或编辑器打开期间、在观看者的区域线程上回调，所以在 `craftableBy`、`fill`、`infoLines` 里读该玩家背包是安全的。唯一的例外是 `RecipeType.recipes()` 以及 `ViewableRecipe.id()/result()/inputs()`：配方发现的 obtain 索引也会从触发获得的那个线程上遍历它们。这几个方法 要写得轻、只做构造、不碰世界状态。

## 生命周期

`RecipeType` 在 `onEnable` 里注册即可 —— 注册那一刻它不需要 CraftEngine，因为 `recipes()`、`icon()`、`title()` 都是等到书打开时才惰性调用的。

厨锅和砧板配方不一样：它们的结果和容器是实打实的 `ItemStack`，必须等 CraftEngine 物品加载完才能注册。 FarmersDelight 自己也是把配方加载推迟到 `CraftEngineReloadEvent` 的，你也在同一个事件里注册，并在之后每次 CE 重载时重新注册。

在 `onDisable` 里反注册，免得 `/plugman` 之类的热卸载在书里留下一个死类型：

```java
@Override
public void onDisable() {
    if (FarmersDelightApi.get().isAvailable()) {
        FarmersDelightApi.get().unregisterRecipeType("fdaddon:example");
        FarmersDelightApi.get().unregisterCookingPotRecipe("fdaddon:example_stew");
        FarmersDelightApi.get().unregisterCuttingBoardRecipe("fdaddon:example_cut");
    }
}
```

## id 约定，以及一条硬规则

类型 id 和配方 id 都是普通字符串。请加命名空间（`brewinandchewin:keg`、`fdaddon:example`），避免和别的附属撞车。

**类型 id 和配方 id 都不能含空格。** 配方发现把解锁状态存成一整个字符串 `"<typeId> <recipeId>"`，读的时候按第一个 空格切开。任何一边带空格，都会把这条记录彻底弄坏。同样的存储规则还意味着：**改配方 id 会让所有玩家对它的解锁记录 成为孤儿。** 在你把一个可能还想改的 id 发出去之前，请先读[配方发现](recipe-discovery.md)。
