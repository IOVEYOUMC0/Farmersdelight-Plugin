
[English](../en/effects.md)
# 效果：营养与舒适

农夫乐事在原版药水效果之外新增了两种自定义食物增益：**营养（Nourishment）**和**舒适（Comfort）**。它们无法酿造——只能靠吃对食物获得。与原版效果不同，它们显示在**Boss 血条**上，而不是背包里的效果列表中。

## 营养（Nourishment）

**作用：** 营养效果生效期间，你的饥饿值停止流失。用游戏机制来说，它会不断重置那些本会侵蚀你饱和度与饥饿值的消耗值，因此一个吃饱的玩家在整个持续时间内都能保持饱腹。（如果你的生命值正靠饱和度回复，这份回复不受影响——营养不会干扰原版的回血。）

**如何获得：** 吃一份丰盛的熟食。营养是你真正下厨的回报，菜越好，效果持续越久。持续时间遵循原模组的分级：

| 持续时间 | 示例菜肴 |
|----------|----------|
| 30 秒 | `farmersdelight:cooked_rice` |
| 60 秒 | `farmersdelight:bone_broth`、`farmersdelight:bacon_and_eggs`、`farmersdelight:ratatouille` |
| 3 分钟 | `farmersdelight:beef_stew`、`farmersdelight:vegetable_soup`、`farmersdelight:chicken_soup`、`farmersdelight:mushroom_rice`、`farmersdelight:steak_and_potatoes` 等 |
| 5 分钟 | `farmersdelight:pumpkin_soup`、`farmersdelight:noodle_soup`、`farmersdelight:roast_chicken`、`farmersdelight:honey_glazed_ham`、`farmersdelight:shepherds_pie` 及其他宴席 |

少数原版汤类也会给予它——`minecraft:mushroom_stew`、`minecraft:beetroot_soup` 和 `minecraft:rabbit_stew` 都能给 3 到 5 分钟。在效果生效期间再吃一份营养食物，会将其刷新为两者中剩余时间较长的那个。

## 舒适（Comfort）

**作用：** 在你没有以其他方式回血时，舒适会随时间缓慢治疗你。它是原模组更早期的治疗增益。

**可用性：** 原模组已弃用舒适、转而主推营养，本移植版**默认关闭**舒适——默认没有任何食物授予它。该机制已完整内置，因此服主如果想把它找回来，可以在配置中开启并为其指定食物。若你的服务器启用了它，其行为与显示都与营养完全一致，只是用自己的一条 Boss 血条。

## Boss 血条显示

生效中的营养 / 舒适会以 Boss 血条的形式出现在屏幕顶部，显示效果名称和剩余时间，血条随效果耗尽而减少：

- **营养**——绿色血条。
- **舒适**——蓝色血条。

每个生效的增益各占一条血条。该增益还能在重新登录和服务器重启后保留——如果你在营养生效时下线，回来时会拿回剩余的时间。

服主可以重新设定血条样式、把显示移到动作栏或玩家列表页脚，或彻底关闭显示；这些都是配置选项，不会改变效果本身的作用。

## 用牛奶清除效果

牛奶在这里清除效果的方式与原版一致，对这些自定义增益同样有效：

- **`minecraft:milk_bucket`**——清除**一切**：你所有生效中的自定义增益（营养、舒适，以及附属内容添加的任何效果），连同牛奶通常会移除的原版效果一并清空。
- **`farmersdelight:milk_bottle`**——更轻量的选择。它从你生效中的原版药水效果与增益里**随机移除一个**，并返还给你一个空玻璃瓶。当你想赌掉某个单独的坏效果、又不想把好效果一起抹掉时，用它。

所以，如果你想留住营养效果，就别喝整桶牛奶。
