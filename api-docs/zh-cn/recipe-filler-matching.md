---
icon: compass-drafting
---

[English](../en/recipe-filler-matching.md)

# RecipeFiller 与 IngredientMatching

两块相关的东西：把配方原料从玩家背包搬进你工作站的 Fill 按钮，以及和厨锅用同一套语义回答「这些槽位能不能满足这些 原料」的匹配器。

## RecipeFiller

```java
@ApiStatus.OverrideOnly
public interface RecipeFiller {
    boolean fill(Player player, ViewableRecipe recipe);

    default boolean onBack(Player player);   // false
}
```

filler 是逐次打开书时提供的，不是逐类型提供的：

```java
FarmersDelightApi.get().openRecipeBook(player, "brewinandchewin:keg", new KegRecipeFiller(posKey));
```

要**为这次打开的那一个具体工作站新建一个 filler**。酒桶的 filler 在构造函数里吃进酒桶的坐标键，正是这一点让 Fill 和返回作用&#x4E8E;_&#x90A3;一&#x4E2A;_&#x9152;桶，而不是「某个酒桶」。一个全局共享的单例 filler 根本无从知道该填哪个站。

没有工作站时传 `null` —— 此时独立的书上根本不会出现 Fill 按钮。

### fill(Player, ViewableRecipe)

玩家点击详情页 `fill` 格子时调用。这个按钮只有在**同时**满足「传了 filler」「详情布局里映射了 `fill` 格子」 「配方解析成功」时才会画出来。

只要填进去了任何东西就返回 `true`。返回 `false` 时 FarmersDelight 会给玩家发「缺少原料」提示 —— 所以对「玩家没有 这些物品」这种情况，`false` 就是正确答案，而不是错误信号。

调用发生在**点击玩家的区域线程**上，在 Folia 上这和工作站所在的区域线程不是一个线程。这是实现 `fill` 时最重要的一 条事实：读玩家背包没有代价，但往一个由别的区域在 tick 的方块容器里写就有代价。BAC 的做法是给「从背包取 → 写进酒 桶」这整段加上酒桶的逐键锁，使它相对于跑在酒桶自己区域上的发酵 ticker 是原子的。

**实现必须防刷：放进去多少，就必须从玩家身上扣掉多少。** 惯用写法是一次只取一个，并且放进去的就是取出来的那一个：

```java
private static ItemStack takeOne(PlayerInventory inventory, Predicate<ItemStack> match) {
    for (int i = 0; i < inventory.getSize(); i++) {
        ItemStack item = inventory.getItem(i);
        if (item != null && !item.getType().isAir() && match.test(item)) {
            ItemStack one = item.clone();
            one.setAmount(1);
            if (item.getAmount() <= 1) {
                inventory.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                inventory.setItem(i, item);
            }
            return one;
        }
    }
    return null;
}
```

先扣再放，绝不能反过来，更不能「先放，再试着扣」—— 这才能保证中途失败时不会凭空多出物品。

### onBack(Player)

玩家在「从你的工作站打开的那本书」的**顶层页面**上点击 `back` 时调用。重新打开工作站界面并返回 `true`；返回 `false`（默认）则让书直接关闭。

准确的触发条件是：只在列表页；只在这本书是以单一独立类型打开的情况下（也就是 `openRecipeBook(player, typeId, filler)`，以及全服只注册了一个类型的情形）；并且只在书的导航历史为空时。从详情页 返回会回到列表；从跳转过来的详情页返回会回到跳转前那条配方。`onBack` 是最后一站，不是每一次 back。

```java
@Override
public boolean onBack(Player player) {
    return reopenKeg(player);
}
```

## IngredientMatching

```java
public final class IngredientMatching {

    public static <Slot, Ingredient> boolean matchesIngredients(
            List<Ingredient> required,
            List<Slot> slots,
            boolean exactSlots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount);
}
```

一个泛型的两趟原料匹配器，刻意和 Bukkit 解耦：由你提供 `matcher`（「这个槽位满足这条原料吗」）和 `initialAmount` （「这个槽位提供多少个原料单位」）。因为不碰任何 Bukkit 类型，它无需起服就能做单元测试，FarmersDelight 也确实为它 带了测试。

它就是厨锅和酒桶在用的那套逻辑，所以调用它，是让附属工作站的匹配语义和 FarmersDelight **一致**、而不只是「看起来 差不多」的办法。

### exactSlots

| 取值      | 规则                                                     |
| ------- | ------------------------------------------------------ |
| `true`  | 已填槽位数必须**等于**原料数 —— 不允许有多余槽位。                          |
| `false` | 允许多余的已填槽位，但**每一个**都必须装着这条配方本身用得上的物品。装着无关物品的槽位仍然会让匹配失败。 |

两者是要按这个顺序依次尝试的。先跑精确一趟，可以让精确配方在宽松配方之前胜出 —— 恰好 4 格甜菜根的甜菜汤，会赢过 任何同样能容忍这 4 格的配方。随后的宽松一趟才用来覆盖「同一种原料摊在多个槽里」的情况，比如 1 份米的配方摆了 3 格 米；同时它依旧会在锅里混进无关物品时拒绝匹配。

BAC 的酒桶正是这么写的：

```java
private boolean matchesIngredientsTwoPass(List<KegIngredient> required, List<ItemStack> present) {
    return matchesIngredients(required, present, true)
            || matchesIngredients(required, present, false);
}

static boolean matchesIngredients(List<KegIngredient> required, List<ItemStack> present, boolean exactSlots) {
    return IngredientMatching.matchesIngredients(
            required, present, exactSlots,
            (slot, ingredient) -> ingredient.test(slot), slot -> 1);
}
```

注意酒桶在调用前先滤掉了空 stack，并且给每个槽位的预算恒定为 1。厨锅则是精确一趟传 `slot -> 1`、宽松一趟传 `ItemStack::getAmount` —— 精确一趟必须强制「每个已填槽位恰好承担一条原料」，因为那正是它之后要消耗的。

### 为什么不用贪心

这里的指派被建模成带增广路的二分匹配（匈牙利 / Kuhn 算法），而不是贪心首次匹配。这不是过度设计：只要原料表达式存在 重叠，贪心首次匹配就是**错的**。当一个宽泛的表达式（标签或或选）排在一个属于它子集的更窄表达式之前，贪心会让宽泛的 那条抢走窄的那条唯一能用的槽位，于是一个客观存在的匹配被漏掉。这个匹配器不受原料顺序和槽位顺序影响，总能找出可行 指派。

`initialAmount` 为 _a_ 的槽位提供 `min(a, 原料数)` 个可互换单位 —— 没有任何一条原料需要超过一个单位，而原料总共也 只有那么多条。负的 `initialAmount` 会被钳到 0。

### 在 filler 里用它

`FDAddonTemplate` 的 filler 用它在动手之前先回答「这个背包能不能满足这条配方」：

```java
@Override
public boolean fill(Player player, ViewableRecipe recipe) {
    List<ItemStack> required = recipe.inputs();
    if (required == null || required.isEmpty()) {
        return false;
    }
    List<ItemStack> slots = new ArrayList<>();
    for (ItemStack content : player.getInventory().getContents()) {
        if (content != null) {
            slots.add(content);
        }
    }
    boolean canFill = IngredientMatching.matchesIngredients(
            required,
            slots,
            false, // exactSlots=false: extra, unrelated inventory items are allowed
            (slot, ingredient) -> FarmersDelightItems.matchesId(slot, FarmersDelightItems.idOf(ingredient)),
            ItemStack::getAmount);
    return canFill;
    // A real filler would now remove one matching item per ingredient and place it into the station.
}
```

这里两个类型参数都落到了 `ItemStack` 上：背包槽位是 `Slot`，配方里已解析好的输入 stack 是 `Ingredient`。真实的工作 站里，原料应当是你自己的原料对象、带自己的 `test` 方法，就像上面酒桶的例子那样。

`FarmersDelightItems.idOf` / `matchesId` 是能识别 CraftEngine 的 id 辅助方法 —— 用它们，别用裸 `Material` 比较， 否则底层材质相同的不同自定义物品会互相匹配上。
