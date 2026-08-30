---
icon: book-bookmark
---

[English](../en/recipe-book-layout.md)

# RecipeBookLayout 与打开配方书

默认情况下，注册进来的 `RecipeType` 会渲染在 FarmersDelight 的共享配方书里，样式由服务端 `gui.yml` → `recipe-book-gui` 决定。这很省事，但只要有几个附属都注册了类型，它们就得共用一个分类菜单。而只要从 `RecipeType.listLayout()` / `RecipeType.detailLayout()` 返回一个 `RecipeBookLayout`，FarmersDelight 就会把**你的** 类型渲染成一本独立的书：你的标题、你的格子、你的装饰。

## 接口

```java
@ApiStatus.OverrideOnly
public interface RecipeBookLayout {
    Component title();
    int rows();
    List<String> layout();
    Map<Character, String> legend();

    default Map<String, ItemStack> decorations();      // Map.of()
    default int size();                                // rows() * 9
    default List<Integer> slotsByType(String role);
    default int firstSlotByType(String role);          // 没有则 -1
}
```

默认的 `slotsByType`/`firstSlotByType` 会从 `layout()` + `legend()` 推出格子下标，所以一个纯数据 record 就是完整的 实现。两个真实附属都是这么写的：

```java
public record SimpleRecipeBookLayout(Component title, int rows, List<String> layout,
                                     Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {
}
```

### title()

原样使用。FarmersDelight 不会对它做任何解析 —— 如果你想要 CraftEngine 的 `<image:ns:id>` / `<shift:N>` 背景字形， 请自己在构造 `Component` 之前解析好，因为持有 CraftEngine 访问权的是你的附属。

**列表标题**支持两个字面占位符，每次绘制时替换：`{page}`（从 1 开始的当前页）和 `{total}`（总页数）。 **详情标题不支持** —— 它是逐字使用的。

### rows() 与 size()

`rows()` 取 1–6，`size()` 就是 `rows() * 9`，窗口按这个尺寸创建。

### layout() 与 legend()

`layout()` 每行一个字符串，每行最多 9 个字符。第 8 列之后的字符会被忽略，超出 `size()` 的格子下标会被跳过。每个 字符通过 `legend()` 映射到一个**角色名**。没有 legend 条目的字符留空。

### decorations()

按角色名给出的静态物品，原样使用（放置时克隆），所以这里放 CraftEngine 自定义物品没问题。

## 角色

角色分两类，行为不同 —— 这也是布局最常见的一个坑。

**动态角色**由配方书自己填，**绝不**会被当作静态 chrome 从 `decorations()` 画上去：

```
category  recipe  ingredient  result  prev_page  next_page  fill  filter  switch  progress
```

**其余一切**（包括 `back`、`background` 以及你自创的任何角色）都是静态装饰：只要 `decorations()` 里有这个角色名的 条目，就会画进所有映射到它的格子。

微妙之处在于：有几个动态角色其实&#x662F;_&#x6309;钮_，配方书决定要放这个按钮时，仍然会按角色名去 `decorations()` 里取物品。 所以 `prev_page`、`next_page`、`fill`、`filter`、`filter_active`、`switch` 虽然是动态角色，也必须在 `decorations()` 里有条目 —— legend 负责给它格子，decorations 负责给它物品。`back` 不一样：它是静态的，由 chrome 那一遍画出来， 处理点击时再反查它的格子。

各角色的作用：

| 角色                        | 页面    | 行为                                                                |
| ------------------------- | ----- | ----------------------------------------------------------------- |
| `recipe`                  | 列表    | 每格一个配方图标。`recipe` 格子的数量**就是**每页容量。                                |
| `prev_page` / `next_page` | 列表    | 只有存在对应页时才放置。                                                      |
| `filter`                  | 列表    | 「仅可制作」开关。只要该角色有格子就显示。开启状态下优先取 `filter_active` 装饰，取不到则退回 `filter`。 |
| `switch`                  | 列表    | 只有 `RecipeType.switchTarget()` 非 null 时才放置。                       |
| `back`                    | 列表、详情 | 静态物品，点击执行返回。                                                      |
| `ingredient`              | 详情    | 按顺序由 `ViewableRecipe.inputs()` 填充。                                |
| `result`                  | 详情    | 只用第一个格子，取 `ViewableRecipe.result()`，并把 `infoLines` 作为 lore。       |
| `fill`                    | 详情    | 只有向 `openRecipeBook` 传了 `RecipeFiller` **且**配方解析成功时才放置。           |
| `progress`                | 详情    | 烹饪进度动画，见下。                                                        |
| _你自己的角色_                  | 详情    | 由 `ViewableRecipe.displaySlots()` 填充。                             |
| `category`                | —     | 在附属布局里没有意义，见下。                                                    |

### 分类菜单永远不归你

`category` 之所以出现在动态角色表里，是因为**共享**书要用它。分类菜单永远从服务端 `gui.yml` 画出来，绝不会取自 `RecipeBookLayout` —— 你的布局只覆盖列表页和详情页。这是有意为之：独立的书本来就跳过分类菜单。

### progress 角色

详情页里映射到 `progress` 的格子会放置单个 `farmersdelight:animated` CraftEngine 物品。竖向动画纹理由客户端播放，服务器不再逐 tick 替换槽位，减少发包。若物品尚未加载（例如 CE 正在重载），会临时使用浅灰色玻璃板，下一次打开详情页时重试。

这个角色你什么都不用提供，映射一个字符上去就行。

## 一对完整的布局

取自 `FDAddonTemplate` 的 `ExampleRecipeType`：

```java
@Override
public RecipeBookLayout listLayout() {
    return new Layout(
            Component.text("Example Recipes", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            6,
            List.of("RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "PXXXBXXXN"),
            Map.of('R', "recipe", 'P', "prev_page", 'N', "next_page", 'B', "back", 'X', "background"),
            Map.of("background", filler(Material.GRAY_STAINED_GLASS_PANE),
                    "prev_page", named(Material.ARROW, "Previous"),
                    "next_page", named(Material.ARROW, "Next"),
                    "back", named(Material.BARRIER, "Close")));
}

@Override
public RecipeBookLayout detailLayout() {
    return new Layout(
            Component.text("Example Recipe", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            6,
            List.of("XXXXXXXXX",
                    "XIIIXTXRX",
                    "XXXXXXXXX",
                    "XXXXXXXXX",
                    "XXXXXXXXX",
                    "XXXXBXFXX"),
            Map.of('I', "ingredient", 'R', "result", 'T', "tool", 'F', "fill", 'B', "back", 'X', "background"),
            Map.of("background", filler(Material.GRAY_STAINED_GLASS_PANE),
                    "fill", named(Material.HOPPER, "Fill ingredients"),
                    "back", named(Material.ARROW, "Back")));
}

/** A plain data RecipeBookLayout; FD's default methods derive slot lookups from layout+legend. */
private record Layout(Component title, int rows, List<String> layout,
                      Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {
}
```

这个列表页每页 45 条配方。`'T'` 映射到 `"tool"`，它不是已知角色，所以由 `ViewableRecipe.displaySlots().get("tool")` 填充。

不要把布局写死，应该从你自己的 `gui.yml` 里读，让服主能改主题。BAC 就是从磁盘读 `KegRecipeBookConfig`，并在重载时 换掉布局：

```java
private volatile KegRecipeBookConfig book;

public void setBook(KegRecipeBookConfig book) {
    this.book = book;
}

@Override
public RecipeBookLayout listLayout() {
    KegRecipeBookConfig b = book;
    return b == null ? null : b.list();
}
```

把 volatile 字段先读进一个局部变量、每次调用只读一次，是有意的：`/reload` 可能在两次读之间把配置换掉。

## 打开配方书

```java
public void openRecipeBook(Player player);
public void openRecipeBook(Player player, RecipeFiller filler);
public void openRecipeBook(Player player, String typeId, RecipeFiller filler);
public void openRecipeEditor(Player player, String typeId, String recipeId);
```

* `openRecipeBook(player)` —— 覆盖所有已注册类型的共享书，只读（没有 Fill 按钮）。
* `openRecipeBook(player, filler)` —— 共享书，带一个绑定到你工作站的 Fill 按钮。
* `openRecipeBook(player, typeId, filler)` —— 直接打开该类型的独立书，绝不走共享分类菜单。如果 `typeId` 没注册， 会**退回共享书**而不是失败。没有工作站要填时 `filler` 传 `null`。

这些都会打开界面，因此必须在持有该玩家的线程上调用（Folia 上是区域线程）。从你自己针对该玩家的界面点击处理里调用 本来就是对的：

```java
if (raw == gui.recipeSlot()) {
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player) {
        FarmersDelightApi.get().openRecipeBook(player, BrewinConstants.BLOCK_KEG,
                new KegRecipeFiller(key));
    }
    return;
}
```

还有一个行为值得知道：当整个服务器只注册了**一个** `RecipeType` 时，`openRecipeBook(player)` 和 `openRecipeBook(player, filler)` 会跳过分类菜单，直接打开那个类型的列表。不要依赖分类菜单一定存在。

配方书是纯只读导航：书里的每一次点击都会在你的代码看到之前被取消，所以不可能从配方书里搬出或刷出物品。
