---
icon: sword
---

# 小刀掉落

包名：`com.huidu.farmersdelight.api.loot` 类：`FarmersDelightKnifeDrops` —— `final`，私有构造，`@ApiStatus.NonExtendable`。

用小刀击杀生物时的额外掉落规则注册接口，也就是 FarmersDelight 火腿、皮革、羽毛、线这些掉落背后的那套规则。 服主在 `config.yml` 里配置它们；这个门面是给需要在运行时增加规则的插件用的 —— 自带屠宰类物品的附属插件、要加条件 掉落的任务插件等。

## 规则何时触发

当玩家手持匹配的工具击杀匹配类型的**成年**生物，且概率判定通过时，规则触发。

掷出的物品是**加进死亡事件的掉落列表**，而不是直接生成掉落物，因此其它插件的战利品处理会像看待任何原版掉落一样 看到它。

## 接口

```java
public static boolean register(String entityType, String normalItemId, String burningItemId,
                               double chance, double lootingMultiplier,
                               List<String> toolItems, List<String> toolTags);

public static boolean register(String entityType, String normalItemId, String burningItemId,
                               double chance, double lootingMultiplier);

public static boolean     unregister(String entityType);
public static Set<String> entityTypesWithRules();
public static boolean     hasRule(String entityType);
```

### register

| 参数                  | 含义                                                                 |
| ------------------- | ------------------------------------------------------------------ |
| `entityType`        | Bukkit `EntityType` 名称，不区分大小写 —— `"pig"`、`"COW"` 都可以。内部会 trim 并转小写 |
| `normalItemId`      | 要掉落的物品 id，`"ns:id"` 形式。`"minecraft:air"` 或 `null` 表示不掉落            |
| `burningItemId`     | 生物着火死亡时改掉这个 id；传 `null` 则始终用 `normalItemId`                        |
| `chance`            | 基础掉落概率，0..1                                                        |
| `lootingMultiplier` | 击杀工具上每级抢夺给 `chance` 增加的量；传 0 表示忽略抢夺                                |
| `toolItems`         | 算作收割工具的物品 id 列表。为空或 `null` 时回落到全局配置的小刀物品列表                         |
| `toolTags`          | 算作收割工具的物品标签 id 列表，**不带**开头的 `#`。为空或 `null` 时回落到全局小刀标签列表            |

FarmersDelight 不可用（未加载或未启用）、或 `entityType` 为 null / 空白时返回 `false`，否则返回 `true`。

**每个实体类型只有一条规则，不是列表。** 对已有规则（包括内置规则）的类型再注册，是替换。

五参数重载就是七参数版本传 `null, null`，因此规则使用全局配置的小刀物品与标签。

```java
import com.huidu.farmersdelight.api.loot.FarmersDelightKnifeDrops;

// 使用服主配置的"小刀"定义。
FarmersDelightKnifeDrops.register("rabbit", "myaddon:rabbit_cutlet", null, 0.35D, 0.10D);

// 只有本附属自己的屠宰工具能触发，烧死的生物掉熟制版本。
FarmersDelightKnifeDrops.register(
        "sheep",
        "myaddon:raw_mutton_strips",
        "myaddon:seared_mutton_strips",
        0.5D,
        0.05D,
        List.of("myaddon:cleaver"),
        List.of("myaddon:butchering_tools"));
```

### unregister

移除此前通过 `register` 添加的规则。当该实体类型上存在**由本门面注册的**规则时返回 `true`。

它不会动来自 `config.yml` 或内置默认的规则 —— 也不需要动，因为那些规则在下次重载时本来就会回来。

请在插件禁用时调用。

### 查询

`entityTypesWithRules()` —— 当前拥有小刀掉落规则的实体类型名（小写），**来源不限**（内置、配置或本门面）。 FarmersDelight 不可用时返回空集合。

`hasRule(entityType)` —— 该类型是否存在规则，来源不限，不区分大小写。

这两个方法都反映当前生效的规则表，所以通过它们无法区分"你注册的规则"和"服主写在 `config.yml` 里的规则"。需要区分 的话，请自己记录注册过的条目。

## 重载存活

在此注册的规则能扛过 `/fd reload`。已在重载处核实：重载会先用内置默认加 `config.yml` 重建整张规则表，然后把通过本 门面注册的所有规则**盖在上面**，最后一次性写入完成的映射表。

因此注册项能挺过服主的一次重载，你的附属插件不必为此去监听 `FarmersDelightReloadEvent`。

"盖在上面"带来两个后果：

* 对同一实体类型，你的规则会**永久**压过 `config.yml` 的规则，直到你反注册为止。除非覆盖正是你的本意，否则对 FarmersDelight 已覆盖的实体类型（猪、牛、鸡）注册时请保守一些。
* `unregister` 之后，该类型的配置 / 默认规则会在**下一次重载**时恢复，而不是立刻恢复。

## 线程模型

`register` / `unregister` 写的是 `ConcurrentHashMap`，在 `onEnable`、重载处理或 `onDisable` 里调用都安全。查询方法 读的是当前生效的表。

UNKNOWN：从异步线程在 tick 中途注册的规则，能否被同一 tick 内的死亡事件处理看到 —— 该表是并发容器，不会损坏，但源码 没有规定它相对于并发击杀的可见性次序。在启动或重载时注册就不会遇到这个问题。

## 相关页面

* [物品](items.md)
* [事件](events.md)
