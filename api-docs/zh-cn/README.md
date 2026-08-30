
[English](../en/README.md)
# 中文

这里是 FarmersDelight 附属 API 的参考文档——第三方插件用来在服务端内部扩展 FarmersDelight 的那套接口。

## 它是什么，不是什么

这是一套**进程内的 Java API**。你的附属是一个 Bukkit 插件，和 FarmersDelight 跑在同一个 JVM 里；你直接调用这些方法， 拿回来的是真正的 Bukkit 对象。整份文档里没有 HTTP 服务、没有 REST 接口、没有请求/响应格式，也不存在任何鉴权步骤。 所谓"调用 API"，就是调一个 Java 方法而已。

所有内容都在 `com.huidu.farmersdelight.api` 下。入口是静态类 `FarmersDelightApi`，从它出发可以拿到配方、方块、物品、 buff、食物效果、进度以及各个事件类。

## 整体定位

FarmersDelight 建立在 CraftEngine 之上。CraftEngine 负责自定义物品、方块、家具、模型、字体图片和标签； FarmersDelight 负责把这些资源接成真正能玩的服务端逻辑——厨锅、砧板、炉灶、煎锅、作物、食物效果，以及配方界面。 一个附属通常做三类事的组合：带上自己的 CraftEngine 资源并挂上 FarmersDelight 的行为、通过本 API 注册配方和内容、 监听 FarmersDelight 的事件来响应玩家操作。CraftEngine 那一侧是配置而不是代码——行为是在 YAML 里挂的，本 API 是 YAML 表达不了时才需要动的东西。

先看[快速上手](getting-started.md)，把依赖和插件生命周期理顺，再看 [FarmersDelightApi](farmersdelight-api.md)，了解入口以及那套能让同一个附属 jar 兼容多个 FarmersDelight 版本的 特性探测规则。

## 关于 CraftEngine

本文只讲 Farmersdelight 插件的 Java API。

如果你的附属主要是内容向的，请去查看Craftengine的官方wiki。一个体量不小的附属完全可以几乎不写 Java；只有当你想要的行为 CraftEngine 表达不出来时，才需要本 API。

## 稳定性约定

**`com.huidu.farmersdelight.api.**` 是唯一受支持的兼容面。** 这个包以外的内容都是内部实现，
可能在版本更新时变化或删除。api 包里的包级私有类（例如 `SnapshotItems`）同样属于实现细节，不在约定范围内。

请对着 api-only jar（`gradlew apiJar`）编译，而不是完整插件 jar。这样越界会变成一个编译错误，而不是一次线上事故。 详见[快速上手](getting-started.md)。

具体类型上还有 `@ApiStatus` 注解进一步收窄约定：

* `@ApiStatus.NonExtendable`——只能调用，不要继承或实现。
* `@ApiStatus.OverrideOnly`——只能实现，不要自己调用。
* `@ApiStatus.Experimental`——可能会变；依赖它就把 FarmersDelight 版本锁死。
* `@ApiStatus.Internal`——虽然是 public，但不在约定范围内。

每一页都会写明它所覆盖类型上的注解。凡是行为会随 Minecraft 版本或 Folia 的 region 线程而变化的地方，页面里都会明确 指出来，而不是留给你自己去踩。
