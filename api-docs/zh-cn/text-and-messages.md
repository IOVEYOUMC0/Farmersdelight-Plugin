---
icon: text
---

# 文本与消息

`com.huidu.farmersdelight.api.text` 下有两个 final 工具类。`FarmersDelightText` 把模板字符串渲染成 Adventure 的 `Component`；`FarmersDelightMessages` 则是渲染加发送一步到位。两个类都是纯静态、私有构造，只能调用，不能继承 也不能实例化。两者都没有 `@ApiStatus` 注解。

**不要自己去调 MiniMessage。** 裸的 `MiniMessage.deserialize` 不会解析 FarmersDelight 的 `<l10n:>` 翻译标签，也不会 解析 CraftEngine 的 `<image:>` 图片标签。附属自己拼文本的结果通常是：玩家聊天框里直接看到一串 `<image:brewinandchewin:icon_stout>`。

## 模板的渲染顺序

`FarmersDelightText.render` 按下面的顺序处理：

1. 替换 `{key}` 字符串占位符。
2. 按查看者的语言解析 `<l10n:>` / `<lang:>` 翻译标签。
3. 解析 CraftEngine 的 `<image:ns:id>` 与 `<shift:N>` 图片标签，转成图片字体输出。
4. 解析 MiniMessage 与传统 (`&` / `§`) 颜色代码。

`viewer` 允许传 null，此时使用默认语言。

## FarmersDelightText

| 方法                                                                                                            | 返回                | 说明                                                         |
| ------------------------------------------------------------------------------------------------------------- | ----------------- | ---------------------------------------------------------- |
| `render(String template, Player viewer)`                                                                      | `Component`       | 无占位符。                                                      |
| `render(String template, Player viewer, Map<String, String> placeholders)`                                    | `Component`       | 替换 `{key}`。                                                |
| `render(String template, Player viewer, Map<String, String> placeholders, Map<String, Component> components)` | `Component`       | 额外拼入 `{key}` 的 Component 占位符。                              |
| `buildLore(List<String> templates, Player viewer, Map<String, String> placeholders)`                          | `List<Component>` | 一行模板一行结果，并显式关掉斜体。                                          |
| `resolveGlyphs(String text)`                                                                                  | `String`          | 只解析 `<image:>` / `<shift:N>`，其它原样保留。                       |
| `glyph(String glyphId)`                                                                                       | `Component`       | 单个 CraftEngine 图片字形；id 解析不出来时返回空 Component。                |
| `shift(int pixels)`                                                                                           | `String`          | 横向像素偏移字形串，可直接嵌进模板。                                         |
| `serverText(String key)`                                                                                      | `String`          | **服务端**默认语言的纯文本。                                           |
| `serverComponent(String key, Object... args)`                                                                 | `Component`       | `serverText` 用位置参数 `%s` 格式化后包成 `Component.text`。           |
| `translatable(String key, Object... args)`                                                                    | `Component`       | `Component.translatable`，由各客户端按自己的语言渲染，并带服务端解析出的 fallback。 |
| `formatDuration(int seconds)`                                                                                 | `String`          | 原版风格 `mm:ss`，超过一小时为 `h:mm:ss`。负数钳到 `0:00`。                 |

四参数的 `render` 用在占位符本身就是带格式的文本时——比如物品显示名自带图片和颜色，一旦被压成 `String` 就全丢了：

```java
Map<String, Component> components = Map.of("fluid", itemNameComponent);
Component line = FarmersDelightText.render("<gray>Contains {fluid}", viewer, null, components);
```

`resolveGlyphs` 是留给"我自己已经有一套 MiniMessage 流程"的场景。BAC 就用它先把 CraftEngine 图标塞进字符串，再自己 反序列化：

```java
String glyph = FarmersDelightText.resolveGlyphs("<image:brewinandchewin:icon_" + slug + ">");
```

### serverText、serverComponent 和 translatable 怎么选

这是本页最要紧的一处区分。选错了不会立刻报错，只会在另一种语言、另一套材质包的玩家那里出问题。

* **`serverText` / `serverComponent`** 完全在服务端按服务端默认语言解析完，所有查看者看到的是同一份已经渲染好的 文本。`serverText` 依次查 FD 的语言文件、CraftEngine 的 `TranslationManager`、Adventure 的 `GlobalTranslator`， 都查不到就把 key 本身返回。凡是**不应该**随接收方客户端变化的文本都用这一组，比如全服广播。
* **`translatable`** 生成 `Component.translatable(key, args)`，由每个客户端按自己的语言渲染，同时带上服务端解析出的 `.fallback(...)`，这样材质包里缺词条的客户端看到的是可读文本而不是裸的 `namespace.key`。物品 lore、bossbar 标题 这类**本来就该**跟随玩家语言的文本用它。

传给 `translatable` 的参数如果本身不是 Component，会被包成 `Component.text`。`serverComponent` 则会先把 Component 参数纯文本序列化，所以它的结果永远是一个扁平的文本 Component，里面不会嵌套任何东西。

BAC 的酒桶提示是真实的 `translatable` 调用。注意颜色是在外面补的，因为语言文件里的值是不带颜色代码的纯文本：

```java
Component servingsLine = FarmersDelightText.translatable(
        "tooltip.brewinandchewin.servings_line",
        servings, tank.amountMb(), capacity
).color(NamedTextColor.GRAY);
```

在 lore 里嵌物品名时，`translatable` 要配 `FarmersDelightItems.translatableDisplayNameOfNoAnvilOf`，而不是普通的 显示名接口——lore 不应该跟着铁砧改名走。四种显示名渲染各自的适用场景见[物品](items.md)。

`formatDuration` 是为遵循 `"<name> [%s]"` 约定的 buff bossbar 标题准备的。它刻意手写而没用 `String.format`，因为 它位于 PlaceholderAPI 的热路径上。参见[自定义 buff 与 Bossbar](buffs.md)。

## FarmersDelightMessages

每个方法都先用 `FarmersDelightText.render` 按目标玩家渲染模板，再投递。

| 方法                                                                                                                                                        | 投递方式    |
| --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `send(Player player, String template)`                                                                                                                    | 聊天栏     |
| `send(Player player, String template, Map<String, String> placeholders)`                                                                                  | 聊天栏     |
| `actionBar(Player player, String template)`                                                                                                               | 动作栏     |
| `actionBar(Player player, String template, Map<String, String> placeholders)`                                                                             | 动作栏     |
| `title(Player player, String titleTemplate, String subtitleTemplate, int fadeInTicks, int stayTicks, int fadeOutTicks, Map<String, String> placeholders)` | 主标题与副标题 |

`send` 和 `actionBar` 在 player 或 template 为 null 时直接返回。`title` 只判 player 非空：两个模板都允许为 null， 渲染成空行——想只显示副标题就是这么做的。`title` 没有更短的重载，没有占位符时传 `Map.of()`。

tick 时长按每 tick 50 毫秒换算并且下钳到 0，所以传负数不会抛异常，等同于 0。

下面就是全部调用形态，取自 FDAddonTemplate：

```java
FarmersDelightMessages.send(player, "<green>Hello {name}!", Map.of("name", player.getName()));
FarmersDelightMessages.actionBar(player, "<gold>Saved");
FarmersDelightMessages.title(player, "<aqua>Title", "<gray>Subtitle", 10, 40, 10, Map.of());
```

## 线程要求

`FarmersDelightMessages` 的每个方法都会操作玩家，因此必须在该玩家所属的线程上调用——Paper 上是主线程，Folia 上是该 玩家所在的 region 线程。不要从异步任务里调。如果结果来自异步逻辑，先切回来，region 正确的调度入口见[调度](scheduling.md)。

`FarmersDelightText` 只负责构造 `Component`，不投递任何东西，源码里也没有写它的线程要求——它的翻译与字形查找能否在 主线程之外安全调用属于 UNKNOWN。带 `viewer` 的重载会读该玩家的语言设置，所以稳妥的做法是：打算在哪个线程发送，就在 哪个线程渲染。

## 相关页面

* [物品](items.md)
* [自定义 buff 与 Bossbar](buffs.md)
* [调度](scheduling.md)
