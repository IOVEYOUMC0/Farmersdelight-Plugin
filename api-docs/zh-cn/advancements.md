---
description: 包名：com.huidu.farmersdelight.api.advancement
icon: calendar-check
---

[English](../en/advancements.md)

# 进度

| 类型                           | 形态                   | `@ApiStatus`     |
| ---------------------------- | -------------------- | ---------------- |
| `FarmersDelightAdvancements` | 静态门面，`final`，私有构造    | `@NonExtendable` |
| `AdvancementTree`            | 流式构建器，`final`，包级私有构造 | 无                |

面向附属插件的稳定接口，封装 FarmersDelight 对 UltimateAdvancementAPI（UAA）的接入。两类用途：

1. 按 id 授予 / 撤销 / 查询 **FarmersDelight 自带**的进度。
2. 用纯数据注册**你自己的进度标签页**，然后用带 `tabId` 前缀的重载去授予和查询。

**没有任何 UAA 类型跨过这条边界。** 构建器和定义对象只引用 Bukkit 与 `java` 类型，所以即使服务器没装 UAA，你的 附属插件也能正常编译和加载。绑定 UAA 的构建由 FarmersDelight 在之后完成，并会在 `/fd reload` 时重建。

{% hint style="danger" %}
`AdvancementTree` 的构造是包级私有的 —— 只能通过 `FarmersDelightAdvancements.tree(String)` 获取，不能 `new`。
{% endhint %}

## 可用性

CE 内容包可在 `configuration/` 下的任意 YAML 里用 `farmersdelight_advancements` 根键提供进度树，由 CraftEngine 在加载数据包时读出来交给 FD。根键必须带 `farmersdelight_` 前缀：CraftEngine 自己占用了 `advancements` / `advancement`（其解析实现是空的），用那两个名字的段谁都不会读。命名空间默认取数据包 `pack.yml` 里的 `namespace`；要给一个数据包挂多个命名空间的进度树，写 `farmersdelight_advancements#<命名空间>:`。配置树未填写任何 `x` / `y` 时按 `parent` 关系调用项目 UAA 补丁版的原版布局算法；只要写了坐标就保留手动布局。Java `AdvancementTree` 仍使用调用方提供的坐标。

```java
public static boolean isAvailable();
```

当 UltimateAdvancementAPI 插件已安装**且已启用**、并且 FarmersDelight 的进度系统已启用时返回 true。此值为 false 时，门面上其它所有方法都是 null 安全的空操作（查询返回 `false`，void 方法什么也不做），所以加这层判断是为了打一条 清晰的日志，而不是为了避免崩溃。

两个真实附属插件都是同一个开头：

```java
if (!FarmersDelightAdvancements.isAvailable()) {
    log.info("Advancements disabled (UltimateAdvancementAPI not loaded).");
    return false;
}
```

这属于正常的软依赖空操作，不是错误。

## FarmersDelight 自带标签页

```java
public static void    award(Player player, String advancementId);
public static void    awardCriteria(Player player, String advancementId, String criterion);
public static void    revoke(Player player, String advancementId);
public static boolean has(Player player, String advancementId);
public static void    showFarmersDelightTab(Player player);
```

`advancementId` 是裸 id，不带命名空间也不带标签页前缀。自带标签页就是下面这 23 个节点——这是完整清单，顺序 与 `AdvancementManager` 的声明顺序一致：

* `root`、`craft_knife`、`place_campfire`、`get_fd_seed`、`get_ham`、`harvest_straw`、 `place_organic_compost`、`use_cutting_board`、`obtain_netherite_knife`、`use_skillet`、`place_skillet`、 `place_cooking_pot`、`place_feast`、`master_chef`、`eat_nourishing_food`、 `hit_raider_with_rotten_tomato`、`get_mushroom_colony`、`plant_rice`、`plant_all_crops`、 `get_organic_compost`、`get_rich_soil`、`hoe_rich_soil`、`harvest_ropelogged_tomato`。

其中是多任务进度的有**两个**，不是一个：

* `master_chef`——它的 criterion 是 27 个 FarmersDelight 菜品物品名（`mixed_salad`、`cooked_rice`、 `beef_stew`……`gleaming_salad`），吃掉对应菜品各授予一项。
* `plant_all_crops`——它的 criterion 是 19 个作物名（`wheat`、`beetroot`、`carrot`、`potato`、`cabbage`、 `tomato`、`onion`、`rice`、`melon`、`pumpkin`、`sweet_berries`、`sugar_cane`、`kelp`、`cocoa`、 `nether_wart`、`chorus_flower`、`brown_mushroom`、`red_mushroom`、`glow_berries`），种下对应作物各授予 一项。正是它那些原版子任务保证了：无论 FarmersDelight 的作物出什么事，它始终可获得。

这一点关系到下面两条规则：按 id 授予时，这**两个**都会展开成各自的全部子任务；要单独授予其中一项，都得用 `awardCriteria`。

授予任何非 root 进度时会先自动补授标签页的 root，避免玩家出现悬空节点。

按 id 授予**多任务**进度会把它的**所有**子任务一并授予。只想授予单个子任务时用 `awardCriteria`。

未知的 `advancementId` 会被忽略；只有在 FarmersDelight 开启调试模式时才会记录日志。

## 构建附属标签页

```java
public static AdvancementTree tree(String tabId);
public static void            unregister(String tabId);
```

`tree(tabId)` 开始构建一棵新树。用同一个 `tabId` 重复注册会替换掉之前的那棵。`unregister` 会移除定义并销毁已构建的 UAA 标签页 —— 请在 `onDisable` 里调用。

### 构建器

```java
public AdvancementTree root(String id, ItemStack icon, String title, String description, String background);

public AdvancementTree advancement(String id, String parentId, ItemStack icon, String title,
                                   String description, String frame, float x, float y);

public AdvancementTree multiTask(String id, String parentId, ItemStack icon, String title,
                                 String description, String frame, float x, float y, List<String> criteria);

public AdvancementTree requires(String advancementId, String... craftEngineIds);
public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds);

public boolean register();
```

* **有且只有一个 root。** `root` 固定放在网格 0,0，边框为 `task`；`background` 是贴图路径，传 `null` 用默认值。
* `frame` 取 `"task"`、`"goal"`、`"challenge"` 之一。
* `x` / `y` 是 `float` 网格坐标 —— 直接传 int 字面量没问题，会自动加宽。
* `multiTask` 在所有具名 criterion 都被授予后完成。
* `register()` 在没有 root、FarmersDelight 不可用、或构建失败时返回 `false`；被接受时返回 `true` —— 注意"被接受" 包含进度系统尚未就绪的情况：此时定义会被保存下来，等系统就绪后再构建标签页。

### 翻译键

标题和描述是客户端翻译键，由客户端资源包解析；也可以直接写字面文本，客户端在键未知时会原样渲染。两个附属插件都 采用 `<tab-id>.advancement.<id>` 与 `<tab-id>.advancement.<id>.desc` 的约定。记得往你的 lang 文件里加上对应条目， 否则键名会被原样显示。

### 一棵真实的树

摘自 BrewinAndChewin 的 `BrewinAdvancements`：

```java
import com.huidu.farmersdelight.api.advancement.AdvancementTree;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;

public static final String TAB_ID = "brewinandchewin";

AdvancementTree tree = FarmersDelightAdvancements.tree(TAB_ID)
        .root(ROOT, icon("brewinandchewin:beer", Material.HONEY_BOTTLE),
                prefix(ROOT), prefix(ROOT) + ".desc",
                "minecraft:textures/block/spruce_planks.png")
        .advancement(PLACE_KEG, ROOT,
                icon("brewinandchewin:keg", Material.BARREL),
                prefix(PLACE_KEG), prefix(PLACE_KEG) + ".desc", "task", 1, 0)
        .advancement(BREW_DRINK, PLACE_KEG,
                icon("brewinandchewin:vodka", Material.POTION),
                prefix(BREW_DRINK), prefix(BREW_DRINK) + ".desc", "task", 2, 0)
        .multiTask(CRAFTING_PROBLEM, BREW_DRINK,
                icon("brewinandchewin:steel_toe_stout", Material.POTION),
                prefix(CRAFTING_PROBLEM), prefix(CRAFTING_PROBLEM) + ".desc",
                "challenge", 3, 0, CRAFTING_PROBLEM_DRINKS);

boolean ok = tree.register();

private static String prefix(String key) {
    return "brewinandchewin.advancement." + key;
}
```

两个附属插件用的图标辅助方法都会在 CraftEngine id 解析不出来时回落到原版 `Material`，值得照抄，因为服主可能已经 把那件物品从资源包里删掉了：

```java
import com.huidu.farmersdelight.api.item.FarmersDelightItems;

private static ItemStack icon(String ceId, Material fallback) {
    ItemStack stack = ceId == null ? null : FarmersDelightItems.create(ceId);
    return stack != null && !stack.getType().isAir() ? stack : new ItemStack(fallback);
}
```

BrewinAndChewin 源码里记了一条注意事项：负数 `y`（用来把节点摆到父节点上方，贴合原版布局）需要一个允许负值的 UAA 构建版本 —— 原版 UltimateAdvancementAPI 在 `AdvancementDisplay` 里会拒绝 `y < 0`。

## 内容依赖声明

要解决的问题是：服主把某件物品从 CraftEngine 资源包里删掉后，只能靠这件物品达成的进度就永远挂在标签页上、再也拿 不到。

```java
public AdvancementTree requires(String advancementId, String... craftEngineIds);
public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds);
```

`requires` 声明某个进度依赖哪些 CraftEngine 内容。当列出的 id **全部**从 CraftEngine 配置中消失时，该进度不会被 构建进标签页，它的子节点会被重新挂到最近的存活祖先上。列表中任意一个 id 仍以物品**或**方块的形式加载着，依赖即 满足。

`requiresCriterion` 对多任务进度的单个 criterion 做同样的事：当它的 id 全部消失时，这个 criterion 会被丢弃，剩下 的仍能完成该进度。没有这层声明的话，删掉一件物品就会让该进度永远差一个子任务。

规则与保险丝：

* 两者都是可选、叠加式的。没有声明依赖的进度或 criterion 始终展示，这也是你完全不调用这两个方法时的行为。
* 只声明那些"缺了就真的不可能达成"的 id。声明过头会把仍然能玩的内容藏起来。
* 对同一个进度再次调用 `requires` 是**替换**上一份列表。
* 标签页 root 永远保留，忽略任何依赖声明。
* 如果某个进度的所有 criterion 都会被丢弃，则改为保留完整列表（一个 criterion 都没有的进度无法注册），并记录一条 警告。
* 变长参数里混进 `null` 或空白会被过滤掉，不会破坏注册。
* 最终决定权在服主：FarmersDelight `config.yml` 里的 `auto-disable-missing` 可以整体关闭该机制，`force-enable` / `force-disable` 列表（条目写成进度 id 或 `tabId:id` 均可）可以覆盖单条决策。

BrewinAndChewin 的声明同时演示了"整类"和"criterion 一对一"两种写法：

```java
tree.requires(PLACE_KEG, BrewinConstants.BLOCK_KEG)
        .requires(BREW_DRINK, FERMENTED_DRINKS.toArray(new String[0]))
        .requires(CRAFTING_PROBLEM, namespaced(CRAFTING_PROBLEM_DRINKS));

for (String drink : CRAFTING_PROBLEM_DRINKS) {
    tree.requiresCriterion(CRAFTING_PROBLEM, drink, NAMESPACE + drink);
}
```

代表整类的列表只有在这一类全没了时才消失，而每个 criterion 只绑定它自己那一件物品。

## 在附属标签页上授予

```java
public static void    award(String tabId, Player player, String advancementId);
public static void    awardCriteria(String tabId, Player player, String advancementId, String criterion);
public static void    revoke(String tabId, Player player, String advancementId);
public static boolean has(String tabId, Player player, String advancementId);
public static void    showTab(String tabId, Player player);
```

语义与 FarmersDelight 自带标签页那组一致：授予非 root 进度会先自动补授 root，按 id 授予多任务进度会授予它的全部 子任务，未知 id 被忽略。对多任务 id 调用 `revoke` 会对称地撤销它的全部子任务，且不会动 root。

这五个方法都会先解析出已构建的标签页，标签页当前未加载时全部空操作 —— 包括 `register()` 已返回 `true` 但进度系统 尚未就绪的那段窗口，以及系统处于下线状态的那段窗口。在这些窗口里发出的授予会被静默丢弃，所以请让授予由玩法事件 驱动，而不是写在启动流程里。

两个附属插件都做了包装，免得调用处到处重复标签页 id：

```java
public static void award(Player player, String advancementId) {
    FarmersDelightAdvancements.award(TAB_ID, player, advancementId);
}

public static void awardCriterion(Player player, String advancementId, String criterion) {
    FarmersDelightAdvancements.awardCriteria(TAB_ID, player, advancementId, criterion);
}

public static boolean has(Player player, String advancementId) {
    return FarmersDelightAdvancements.has(TAB_ID, player, advancementId);
}
```

## 生命周期与重载

你注册的定义会一直保留。真正的 UAA 标签页只在进度系统就绪期间构建 —— 即 CraftEngine 物品加载完成且 UAA 已启用之后 —— 系统下线时销毁，回来时重建。

重建后，FarmersDelight 会对已在线的玩家重新展示每个重建过的标签页（重新授予 root 并重新展示），这样标签页不会从他们 的客户端上消失、非要重新登录才回来。

对附属插件而言，实践上就是：

* 在 `onEnable` 里、FarmersDelight 就绪之后调用一次 `register()`。`/fd reload` 时不需要重新注册。
* 在 `onDisable` 里调用 `unregister(tabId)`。

## 线程模型

所有授予、撤销、查询最终都会调进 UltimateAdvancementAPI，门面自身不做任何调度 —— 你在哪个线程调用，它就在哪个线程 执行。

{% hint style="warning" %}
UNKNOWN：源码没有说明 UAA 自身的线程契约。稳妥的假设是按面向玩家的 Bukkit 状态处理 —— Paper 上在主线程调用，Folia 上在该玩家的区域线程调用。门面会捕获并吞掉底层授予调用抛出的异常（best-effort），因此线程违规可能表现为"进度悄悄 没生效"，而不是一条堆栈。
{% endhint %}

## 相关页面

* [物品](items.md)
* [配方发现](recipe-discovery.md)
* [快速上手](getting-started.md)
