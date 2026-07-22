---
icon: ticket-perforated
---

# RecipeType 与 ViewableRecipe

`RecipeType` 是一个可注册的配方分类：有 id、有标题、有图标。注册之后，FarmersDelight 就能在配方书里渲染你的配方， 而完全不需要知道你内部的配方格式长什么样。Brewin & Chewin 的酒桶是参考实现 —— `KegRecipeType` 把插件原本就有的 `KegRecipe` 适配了上去。

## RecipeType

```java
package com.huidu.farmersdelight.api.recipe;

@ApiStatus.OverrideOnly
public interface RecipeType {
    String id();
    Component title();
    ItemStack icon();
    List<ViewableRecipe> recipes();

    default ViewableRecipe recipe(String id);
    default RecipeEditor editor();          // null
    default RecipeBookLayout listLayout();  // null
    default RecipeBookLayout detailLayout();// null
    default String switchTarget();          // null
}
```

`Component` 是 `net.kyori.adventure.text.Component`，`ItemStack` 是 `org.bukkit.inventory.ItemStack`。

### id()

注册表的键。注册就是一次以 `id()` 为键的 map put，所以用同一个 id 注册第二个类型会**顶掉**第一个。务必加命名空间。 它同时是配方发现存储键的前半段，因此不能含空格，改名会让解锁记录变孤儿。

### title() 与 icon()

两者一起构成共享配方书主菜单里的分类按钮。注意菜单对它们做了什么：图标会被克隆，然后它的显示名会被 **`title()` 覆盖掉**。你挂在图标 stack 上的自定义名字在那里会被丢弃。如果 `icon()` 返回 `null` 或空气，菜单退回 `Material.BOOK`。

`title()` 同时也是列表页和详情页的窗口标题 —— **前提是你没有提供自己的布局**。一旦提供了 `listLayout()`/`detailLayout()`，标题以布局自身的 `title()` 为准，`RecipeType.title()` 就只剩分类按钮这一个用途。

### recipes()

按展示顺序给出的配方快照。每次画列表、每次翻页、每次切换「仅可制作」筛选，以及每次重建配方发现的 obtain 索引， 都会调用它 —— 所以要构造得便宜，并且保证任意线程调用都安全。BAC 每次调用都新建一个 `ArrayList` 装适配对象，这没 问题；不能做的是在这里访问世界状态或阻塞读盘。

### recipe(String id)

默认实现是对 `recipes()` 的线性扫描。每次画详情页、每次点 Fill 按钮、每次跳转，FarmersDelight 都会调它。如果你的 类型有上千条配方，请覆写成 map 查表。

对未知 id 返回 `null` 是正确做法，调用方也是这么预期的 —— 缺失 id 的详情页会画成一张空页面，而不是抛异常。

### editor()

返回一个 `RecipeEditor`，这个类型就能通过 FarmersDelight 的通用编辑器 GUI 在游戏内编辑；返回 `null`（默认值）则是 只读类型。此时 `FarmersDelightApi.openRecipeEditor` 是静默空操作。见 [RecipeEditor](recipe-editor.md)。

### listLayout() / detailLayout()

`null`（默认）表示「把我放进 FarmersDelight 的共享书里渲染」，样式由服务端 `gui.yml` → `recipe-book-gui` 决定。 非 `null` 表示「用我的布局，把我渲染成一本独立的书」—— 你的标题、你的格子、你的装饰物品，绝不会和其他附属挤进同一 个分类菜单。见 [RecipeBookLayout](recipe-book-layout.md)。

两者是分别判断的。只提供 `detailLayout()`，你会得到共享的列表页 + 你自己的详情页。

### switchTarget()

点击书上的 `switch` 按钮时要跳去的兄弟类型 id —— 例如酒桶在「发酵配方」和「倾倒配方」之间来回切。按钮只有在 **两个条件同时成立**时才会画出来：列表布局里有一个格子映射到 `switch` 角色，且 `switchTarget()` 非 null。点击后画 出兄弟类型的第 0 页列表。如果那个 id 没注册，点击不产生任何效果。

要让切换能来回走，两个类型必须互相指向对方。

## ViewableRecipe

```java
@ApiStatus.OverrideOnly
public interface ViewableRecipe {
    String id();
    List<ItemStack> inputs();
    ItemStack result();

    default List<Component> infoLines(Player viewer);        // List.of()
    default ItemStack icon();                                // result()
    default Map<String, List<ItemStack>> displaySlots();     // Map.of()
    default Map<String, JumpTarget> jumpTargets();           // Map.of()
    default boolean craftableBy(Player player);              // true
}
```

### id()

在所属 `RecipeType` 内唯一。用于详情查找、编辑器查找、跳转解析 —— 同时还是逐玩家配方发现的存档键。 **不能含空格，改名会让解锁记录变孤儿。**

### inputs() 与 result()

`inputs()` 是已经解析好的具体 stack，不是原料表达式。它们按顺序填进详情页的 `ingredient` 格子，并按 `min(ingredient 格子数, inputs 数)` 截断 —— 多出来的输入会被静默丢弃，所以布局里的原料格要给够。

`result()` 放进**第一个** `result` 格子。结果为 `null` 或空气时画成 `Material.PAPER`。

### infoLines(Player viewer)

详情页里的补充信息行 —— 烹饪时间、经验，什么都行。它们会作为**结果物品的 lore**应用上去，**覆盖**该 stack 原有的 lore，并强制关闭斜体。列表为空时不动原有 lore。带 `viewer` 参数是为了让你能按玩家做本地化。

### icon()

列表页里显示的 stack，默认取 `result()`。`null`/空气退回 `Material.PAPER`。当配方发现开启且该配方处于锁定状态时， 图标会被锁定占位物整个替换掉。

### displaySlots()

按**自定义角色名**给出的额外展示物品，服务于提供了自己 `detailLayout()` 的类型。详情页会找出所有映射到该角色的 格子，按下标逐一填入你的列表。`null` 或空气的条目会被跳过，那个格子保留 chrome 画上去的东西。

`ingredient` 和 `result` 这两个角色已经由 `inputs()`/`result()` 处理，不必重复。BAC 的酒桶用了 `fluid`、 `fluid_icon`、`fluid_level`、`output_fluid`、`temperature` 和 `ferment_info`。

如果布局把多个格子映射到同一角色，而你只给了一个物品，那只有第一个格子会被填上。BAC 的做法是把同一个 stack 按格子 数重复，填满一整条较宽的仪表：

```java
private static List<ItemStack> repeat(ItemStack item, int count) {
    List<ItemStack> copies = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        copies.add(item);
    }
    return copies;
}
```

### jumpTargets()

让某个展示角色的格子变成可点击：点击后打开另一条配方的详情页，可以是另一个 `RecipeType` 的。

```java
@Override
public Map<String, JumpTarget> jumpTargets() {
    return Map.of("fluid", new JumpTarget("brewinandchewin:keg", producerRecipeId));
}
```

`JumpTarget` 是 `record JumpTarget(String typeId, String recipeId)`。配方书拿 `typeId` 去注册表里查类型，再拿 `recipeId` 去该类型里查配方；任何一步查不到就忽略这次点击，而不是报错。不在这个 map 里（或值为 `null`）的角色就是 不可点击。

跳转的判断排在 `back` 和 `fill` 格子**之后**，所以和这两个按钮共用格子的角色永远不会触发。跳转成功会把当前页压入 配方书的历史栈，因此 `back` 会回到你跳转前的那条配方，多级跳转也能一路回退。

另外注意：角色是拿布局的格子来匹配的，所以只有提供了 `detailLayout()` 并在其中映射了这些角色的类型，跳转才有效。

### craftableBy(Player player)

支撑配方书可选的「仅可制作」筛选。只有当列表布局里有 `filter` 格子**且**玩家把筛选打开时才会被调用 —— 从不显示 筛选按钮的类型永远不会走到这里。一旦被调用，则是在观看者的区域线程上、对该类型的每一条配方、每次画列表都调一遍。 在这里读 `player.getInventory()` 是安全的，做重活不是。

默认返回 `true` 表示「总是显示」，对于无法廉价检查背包的类型，这就是正确答案。

## 注册

```java
FarmersDelightApi.get().registerRecipeType(kegRecipeType);
```

```java
FarmersDelightApi.get().unregisterRecipeType(BrewinConstants.RECIPE_TYPE_KEG_FERMENTING);
```

两者任意线程调用都安全。`null` 类型、或 `id()` 为 `null` 的类型会被静默忽略。注册顺序会被保留，也就是分类在共享菜单 里的排列顺序。两个调用都会顺带让配方发现的 obtain 索引失效，所以新注册类型的配方立刻就能被自动解锁触发到。

## 一个最小实现

```java
package com.example.fdaddon;

import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class ExampleRecipeType implements RecipeType {

    @Override
    public String id() {
        return "fdaddon:example";
    }

    @Override
    public Component title() {
        return Component.text("Example Recipes", NamedTextColor.GOLD);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Material.RABBIT_STEW);
    }

    @Override
    public List<ViewableRecipe> recipes() {
        // ExampleViewableRecipe 是你自己的 ViewableRecipe 实现，由你的配置构造。
        return List.of(new ExampleViewableRecipe());
    }
}
```

不给布局、不给编辑器，这个类型就是 FarmersDelight 共享书里的一个分类。加上 `listLayout()`/`detailLayout()` 可以把 它拆成独立的一本书，加上 `editor()` 可以让它变成可编辑的。
