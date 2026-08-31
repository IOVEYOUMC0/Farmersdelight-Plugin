---
icon: box-isometric
---

[English](../en/blocks-and-stations.md)

# 方块与工作站

这个包解决附属插件的两个问题：**这个方块是什么**，以及**这个工作站现在装了什么**。所有参数和返回值都是 Bukkit 或 `java` 类型（外加本包内的 snapshot record），因此附属插件不需要引入 CraftEngine 依赖就能使用。

本页涉及的类型：

| 类型                      | 形态        | `@ApiStatus`                     |
| ----------------------- | --------- | -------------------------------- |
| `FarmersDelightBlocks`  | 静态门面，私有构造 | `@Experimental`、`@NonExtendable` |
| `FarmersDelightStation` | 枚举，4 个常量  | `@Experimental`                  |
| `CookingPotSnapshot`    | record    | `@Experimental`                  |
| `CuttingBoardSnapshot`  | record    | `@Experimental`                  |
| `SkilletSnapshot`       | record    | `@Experimental`                  |
| `StoveSnapshot`         | record    | `@Experimental`                  |

{% hint style="info" %}
`@ApiStatus.Experimental` 表示签名在 FarmersDelight 版本之间仍可能变动：锁定你编译时的版本，升级时重新核对。 `FarmersDelightBlocks` 上的 `@ApiStatus.NonExtendable` 有双重保障 —— 类本身是 `final` 且构造私有。
{% endhint %}

## 绝不要用 Material 判断工作站

FarmersDelight 的工作站都是 CraftEngine 自定义方块。CraftEngine 通过 Bukkit 暴露的是一个可配置的**伪装材质**，默认情况下所有自定义方块共用同一个，所以下面这种写法什么都判断不了：

```java
// 错误写法。它会匹配服务器上所有自定义方块，或者一个都匹配不到，
// 取决于服主随时可以改的一个配置值。
if (block.getType() == Material.BRICKS) { /* “这是厨锅” */ }
```

这套 API 存在的原因之一，就是让正确写法变得简单：

```java
import com.huidu.farmersdelight.api.block.FarmersDelightBlocks;
import com.huidu.farmersdelight.api.block.FarmersDelightStation;

FarmersDelightStation station = FarmersDelightBlocks.stationOf(block);
if (station == FarmersDelightStation.COOKING_POT) {
    // 确定是厨锅
}
```

`stationOf` 会解析出真实的 CraftEngine 方块状态，并且按方块上挂载的 **behavior** 来识别工作站，而不是按方块 id。 因此服主把工作站换皮成自己的 id、或者附属插件在自己的方块上复用工作站 behavior，都仍然能被识别。

### id 要怎么取

从 CraftEngine 方块状态取 id，看上去最顺手的那条路是个陷阱。状态的 owner 暴露的是一个包着 resource key 的 `Optional`；对这个包装对象调用 `toString`，得到的是包装自身的文本形态（一个 `Optional` 的渲染结果），永远不是 `namespace:path` 形式的裸 id。把它拿去和 `"farmersdelight:cooking_pot"` 比较**恒为 false**，于是被守卫的代码 变成死代码，而且不会有任何报错。id 必须从 resource key 的 location 取。

`blockIdOf` 和 `stationIdOf` 已经这么做了。用它们，不要自己去走 CraftEngine 的状态对象。

```java
String id = FarmersDelightBlocks.blockIdOf(block);   // "farmersdelight:cooking_pot"、"brewinandchewin:keg" 或 null
```

## 线程模型（Folia / Luminol）

`FarmersDelightBlocks` 的每个方法都要读世界状态，调用方必须已经处在拥有该 `block` 的区域线程上：

* 在该方块所在事件的监听器内部，或
* 在通过 `FarmersDelightApi.get().runAtLocation(block.getLocation(), ...)` 调度的任务内部。

从其它区域线程、全局区域或异步任务调用，会撞上 Paper 在 `CraftWorld` 里的线程检查并**抛异常**（moonrise 的 tick-thread 断言）。它表现为你监听器内部抛出的异常，而不是返回 `null`。

单线程 Paper 服务端只有一个区域，主线程调用怎么写都对 —— 这正是这个问题容易被漏掉、直到有人把你的附属插件 放到 Folia 上才暴露的原因。

{% hint style="warning" %}
这些方法不会替你调度。快照必须同步返回，跨区域跳转拿回来的会是另一个 tick 的数据。
{% endhint %}

```java
import com.huidu.farmersdelight.api.FarmersDelightApi;

FarmersDelightApi.get().runAtLocation(block.getLocation(), () -> {
    CookingPotSnapshot pot = FarmersDelightBlocks.cookingPot(block);
    if (pot != null && pot.cooking()) {
        // ...
    }
});
```

```java
public enum FarmersDelightStation {
    COOKING_POT,    // defaultBlockId() = "farmersdelight:cooking_pot"
    CUTTING_BOARD,  // defaultBlockId() = "farmersdelight:cutting_board"
    SKILLET,        // defaultBlockId() = "farmersdelight:skillet"
    STOVE;          // defaultBlockId() = "farmersdelight:stove"

    public String defaultBlockId();
}
```

`defaultBlockId()` 是 FarmersDelight **自带**的工作站 id，它**不等于** `FarmersDelightBlocks.stationIdOf` 的返回值 —— 后者返回的是世界里实际那个方块的 id，在换皮的服务器上两者会不同。只有当你确实是指"原版自带的那个 工作站"时，才去比较方块 id。

```java
public static String                blockIdOf(Block block);
public static String                stationIdOf(Block block);
public static boolean               isStation(Block block);
public static FarmersDelightStation stationOf(Block block);

public static CookingPotSnapshot    cookingPot(Block block);
public static CuttingBoardSnapshot  cuttingBoard(Block block);
public static SkilletSnapshot       skillet(Block block);
public static StoveSnapshot         stove(Block block);
```

`blockIdOf` —— `block` 的 CraftEngine 方块 id（如 `"farmersdelight:cooking_pot"`，或某个附属的 `"brewinandchewin:keg"`）；当它根本不是 CraftEngine 自定义方块时返回 `null`。对任意自定义方块都有效，不限于 FarmersDelight 自己的。

`stationIdOf` —— 当 `block` 是四个工作站之一时返回其方块 id，否则返回 `null`。想分支判断**是哪个**工作站时用 `stationOf`。

`isStation` —— `block` 是否为四个工作站中的任意一个。

`stationOf` —— `block` 是哪个工作站，不是则返回 `null`。

这四个方法都接受 `null` 方块，对应返回 `null` / `false`。

### 四个快照方法

当方块不是对应工作站时，每个方法都返回 `null`。除此之外各有自己的"未被跟踪"情形：

| 方法             | 额外返回 null 的情形                                  |
| -------------- | ---------------------------------------------- |
| `cookingPot`   | 方块实体未加载 —— 区块未加载，或本 tick 刚放下、尚未初始化的锅           |
| `cuttingBoard` | 方块实体未加载。**空砧板仍然返回快照**，只是 `storedItem` 为 `null` |
| `skillet`      | 煎锅未被跟踪 —— 从来没有往里面或上面放过东西                       |
| `stove`        | 炉灶未被跟踪 —— 从来没有往上面放过食物                          |

FarmersDelight 自身未加载或未启用时，它们同样返回 `null`，附属插件不必再单独加判断。

## 快照是什么，能用来做什么

快照是**调用那一瞬间的拷贝**。工作站之后还在继续 tick。把这些值当作一次读数，而不是一个句柄。

适合的用途：在 GUI 或全息里展示工作站内容、做逻辑闸门（"这口锅里已经有成品了吗"）、统计、调试指令、决定要不要 触发你自己的事件。

这套 API 做不到的：写入。每个 `ItemStack` 在进入 record 时会克隆一次，在每次访问器调用时**再克隆一次**，所以你 拿到的东西怎么改都碰不到工作站的真实库存，工作站也永远不会把活的 stack 交到你手上。列表是不可变的，`location()` 返回的也是克隆。

```java
CookingPotSnapshot pot = FarmersDelightBlocks.cookingPot(block);
pot.ingredients().get(0).setAmount(64);   // 能编译，但对任何地方都没有影响
```

{% hint style="warning" %}
这里没有提供任何写入通道。如果你需要改变工作站的内容，请走正常玩法路径（漏斗、玩家交互），或使用工作站自身的 事件接口。
{% endhint %}

### CookingPotSnapshot

```java
public record CookingPotSnapshot(Location location,
                                 List<ItemStack> ingredients,
                                 ItemStack container,
                                 ItemStack mealDisplay,
                                 ItemStack output,
                                 String recipeId,
                                 int progressTicks,
                                 int cookTimeTicks,
                                 int remainingTicks,
                                 boolean heated) {
    public boolean cooking();
    public double progressFraction();
}
```

* `location` —— 锅的方块坐标，对齐到方块角点。
* `ingredients` —— 按布局顺序排列的原料槽。`null` 表示空槽，并且 `null` 会被保留，这样槽位下标才有意义。
* `container` —— 碗 / 瓶槽的内容，空则为 `null`。
* `mealDisplay` —— 锅的 **output** 槽里的第一件物品，也就是当前展示在锅里的成品菜；没有则为 `null`。
* `output` —— 锅的 **pending-output** 槽里的第一件物品，也就是刚做好、尚未取走的菜；该槽为空则为 `null`。
* `recipeId` —— 正在烹饪的配方 id，空闲时为 `null`。取自匹配到的 `CookingPotRecipe` 的 id。
* `progressTicks` / `cookTimeTicks` —— 已累计的 tick 数与总共需要的 tick 数。空闲且未设置时长时 `cookTimeTicks` 为 0。
* `remainingTicks` —— `cookTimeTicks - progressTicks`，下限截到 0。
* `heated` —— 锅下方是否有配置好的热源（或架在热源上的导热方块）。
* `cooking()` —— `recipeId != null` 时为 true。
* `progressFraction()` —— 0..1；`cookTimeTicks <= 0` 时为 0。

注意上面 `mealDisplay` / `output` 的对应关系：抛开命名不谈，`mealDisplay` 读的是 output 槽，`output` 读的是 pending-output 槽。

### CuttingBoardSnapshot

```java
public record CuttingBoardSnapshot(Location location, ItemStack storedItem, boolean carved) {
    public boolean occupied();
}
```

砧板最多放一组物品。`carved` 表示存放的物品是以"雕刻"（工具）姿态展示，而不是平放。`storedItem != null` 时 `occupied()` 为 true。

### SkilletSnapshot

```java
public record SkilletSnapshot(Location location,
                              ItemStack storedItem,
                              ItemStack skilletItem,
                              String recipeId,
                              int progressTicks,
                              int cookTimeTicks,
                              int remainingTicks,
                              boolean heated,
                              int fireAspectLevel) {
    public boolean cooking();
    public double progressFraction();
}
```

* `storedItem` —— 锅里当前的食物，空则为 `null`。
* `skilletItem` —— 放置该方块时所用的煎锅物品，带着它的附魔；没有则为 `null`。
* `recipeId` —— 正在烹饪的营火配方 key（Bukkit `NamespacedKey` 的字符串形式）；没有匹配时为 `null`。
* `heated` —— 煎锅下方是否有配置好的热源（或导热方块）。
* `fireAspectLevel` —— 煎锅物品上的火焰附加等级，会缩短烹饪时间。
* `cooking()` —— 有配方匹配时为 true。进度只在 `heated` 为 true 时推进。

### StoveSnapshot

```java
public record StoveSnapshot(Location location,
                            List<ItemStack> items,
                            List<Integer> progressTicks,
                            List<Integer> cookTimeTicks,
                            boolean lit,
                            boolean blockedAbove) {
    public int occupiedSlots();
    public double progressFraction(int slot);
}
```

炉灶同时烤多件物品。三个列表是**平行的、长度始终相同** —— 每个烧烤槽位一项，下标与炉灶内部的跟踪方式一致。 炉灶有 6 个槽，因此三个列表都是 6 项。`items` 中的 `null` 表示空槽。

* `lit` —— 炉灶是否在燃烧。未点燃的炉灶不推进进度。
* `blockedAbove` —— 炉灶上方是否有碰撞体遮挡了烧烤区域。
* `occupiedSlots()` —— 有食物的槽位数量。
* `progressFraction(int slot)` —— 该槽的 0..1 进度；越界槽位或未设置时长时返回 0（不会抛异常）。

```java
StoveSnapshot stove = FarmersDelightBlocks.stove(block);
if (stove != null && stove.lit() && !stove.blockedAbove()) {
    for (int slot = 0; slot < stove.items().size(); slot++) {
        ItemStack food = stove.items().get(slot);
        if (food != null) {
            player.sendMessage(food.getType() + " " + (int) (stove.progressFraction(slot) * 100) + "%");
        }
    }
}
```

关于开销：每次访问器调用都会克隆。在逐 tick 的循环里，请先把 `items()` 取到局部变量，不要写在循环条件里。

## 相关页面

* [物品](items.md)
* [事件](events.md)
* [配方包总览](recipes-overview.md)
