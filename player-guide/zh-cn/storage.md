# 储物与板条箱

农夫乐事提供了两种整理收成的方式：把产物压缩成实心方块的**板条箱与捆包**，以及能替你收集掉落物的**篮子**。

## 板条箱与捆包

板条箱就是经典的"9 个物品压成一块"储物方块——很适合把一批收成堆进仓库，需要时再变回物品。每种箱子由整整 3×3 的对应作物合成，并可无序拆包还原成 9 个该物品。

| 方块 | 由什么合成 | 拆包为 |
| --- | --- | --- |
| `farmersdelight:carrot_crate` | 9 `minecraft:carrot` | 9 胡萝卜 |
| `farmersdelight:potato_crate` | 9 `minecraft:potato` | 9 马铃薯 |
| `farmersdelight:beetroot_crate` | 9 `minecraft:beetroot` | 9 甜菜根 |
| `farmersdelight:cabbage_crate` | 9 `farmersdelight:cabbage` | 9 卷心菜 |
| `farmersdelight:tomato_crate` | 9 `farmersdelight:tomato` | 9 番茄 |
| `farmersdelight:onion_crate` | 9 `farmersdelight:onion` | 9 洋葱 |

稻米和稻草有各自的压缩块：

| 方块 | 由什么合成 | 拆包为 |
| --- | --- | --- |
| `farmersdelight:rice_bag` | 9 `farmersdelight:rice` | 9 稻米 |
| `farmersdelight:rice_bale` | 9 `farmersdelight:rice_panicle` | 9 稻穗 |
| `farmersdelight:straw_bale` | 9 `farmersdelight:straw` | 9 稻草 |

板条箱是实心木质方块（用斧头开采）。捆包则较软：**稻草捆**和**稻米捆**都可燃，也都能像干草捆一样缓冲坠落。

## 橱柜

橱柜是正经的储物容器，拥有 **27 格库存**（三行），像木桶一样右键开启。每种木头各有一款——`farmersdelight:oak_cabinet`、`farmersdelight:spruce_cabinet`、`farmersdelight:birch_cabinet`、`farmersdelight:jungle_cabinet`、`farmersdelight:acacia_cabinet`、`farmersdelight:dark_oak_cabinet`、`farmersdelight:mangrove_cabinet`、`farmersdelight:cherry_cabinet`、`farmersdelight:bamboo_cabinet`、`farmersdelight:pale_oak_cabinet`、`farmersdelight:crimson_cabinet` 和 `farmersdelight:warped_cabinet`。

**合成：** 上下两行各摆三块同种木台阶，中间一行两侧各摆一个同种木活板门：

```
S S S
D   D
S S S
```

（S = 木台阶，D = 木活板门，同一种木头）。橱柜朝向你放置的方向，破坏时掉落其中物品。

## 篮子

篮子（`farmersdelight:basket`）是一种储物容器，还能从它所朝向的方向**收集掉落物**——很适合放在自动农场底部。

**合成：** 竹子与帆布按下列图案摆放（B = `minecraft:bamboo`，C = `farmersdelight:canvas`）：

```
B B
C C
B C B
```

（帆布由稻草制成——4 稻草 → 1 帆布——所以篮子归根结底也是稻草制品。）

**作为储物：** 放置后打开，是一个 **27 格库存**（三行）。它支持漏斗（物品可被管入或抽出），并会根据装填程度输出比较器信号。破坏时掉落其中物品。

**朝向：** 篮子朝向你放置时所贴靠的那一面，也就是它收集的那一面。把它放在方块底面即可让它朝下，接住落到它上面的物品。

### 自动收集掉落物

{% hint style="warning" %}
**行为仍在定稿中。** 下述收集掉落物的行为是篮子**应有**的目标行为；该功能正在一项进行中的修复里对齐，当前版本可能尚未完全启用。此处记录目标行为，供你了解预期。
{% endhint %}

已放置的篮子会周期性地**扫描它所朝向的格子**（自身所在格加上朝向方向上的一格），寻找掉落的物品实体并收入库存：

- 它每次拾取之间会间隔片刻（每次拾取后有一段短冷却），而非一次全收。
- 一旦完全装满就停止收集。

这让篮子成为一个简单的定向物品收集器：把一个篮子朝下对准番茄丛、切菜板或生物/作物掉落点，它便会静静地囤起落在它面前的东西——之后下方的漏斗还能把溢出的物品继续运走。
