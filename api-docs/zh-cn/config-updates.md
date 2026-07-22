---
description: 包名：com.huidu.farmersdelight.api.config
icon: folder-open
---

# 让服主的 config.yml 保持最新

## 这个包要解决的问题

Bukkit 的 `saveDefaultConfig()` **只在文件不存在时**才写入内置配置。对于一台从你上个版本之前就一直在跑的服务器， 这意味着：

* 你在后续版本里新增的每一个设置项，在服主的文件里永远缺失，对应功能只能一直读你 `getConfig().getBoolean(path, default)` 里写死的兜底值；
* 你改过名的每一个路径，仍旧以旧名字躺在文件里、没有任何代码去读它，服主精心调过的值彻底失效；
* 你已经废弃的每一个设置项还留在那里，看上去像是还在起作用。

而服主没有任何办法察觉这些。唯一诚实的做法，是插件在每次启动时把服主的文件与当前构建内置的那份对账一遍，同时 一个字都不改动服主设过的值。这就是本包要做的事。

FarmersDelight 用它引导自己的 `config.yml` 和 `gui.yml`；Brewin' And Chewin' 作为附属侧的参考实现，用四行代码 跑完整套流程。

## 四个类型

| 类型                   | 形态                                        | 职责                 |
| -------------------- | ----------------------------------------- | ------------------ |
| `ConfigFileUpdater`  | final 类，静态方法，构造器私有                        | 纯机制，不持有任何状态        |
| `ConfigUpdatePolicy` | final 类，builder                           | 你的数据：重命名、废弃路径、注册表段 |
| `ConfigKeyRename`    | record `(String oldPath, String newPath)` | 一条重命名              |
| `ConfigUpdateReport` | final 类                                   | 本次更新实际做了什么         |

这些类型都不含有任何具体配置文件的知识，每个插件自带自己的表，因此主插件和它的附属可以共用同一套行为，而不必 共用同一种口吻、同一份语言文件或同一套配置表。

由此衍生出两条值得明说的设计决定：

* **API 从不打日志。** 它返回一个 `ConfigUpdateReport`，由调用方用自己的措辞、自己的语言文件去表达。
* **备份失败是被报告而不是被抛出的。** 它出现在 `report.backupError()` 上。丢了这层保险不足以让一次本来正确的 更新作废——但服主有权知道，所以请把它打出来。

{% hint style="warning" %}
`ConfigUpdateReport` 的构造器是包级私有的：你只会收到报告，不会自己造一个。
{% endhint %}

## 推荐入口

```java
public static ConfigUpdateReport updateMainConfig(Plugin plugin, ConfigUpdatePolicy policy)
        throws IOException, InvalidConfigurationException;
```

一次调用即对你插件自己的 `config.yml` 完成整套有序流程：

1. 从 jar 里读出内置 `config.yml`（jar 中没有该资源时返回一份空报告——某个构建确实可能不带这个文件）；
2. 按策略列出的顺序应用重命名；
3. 删除废弃路径；
4. 合并缺失的设置项，连同它们的注释；
5. 若什么都没变，到此为止直接返回，不写文件；
6. 否则先给文件打一份带时间戳的备份，再固定 YAML 输出选项，`saveConfig()`、`reloadConfig()`；
7. 返回报告；若备份写失败，报告里带上 `backupError`。

除非你需要在各步骤之间插入自己的动作，否则请用这个入口而不是逐步调用。步骤之间有严格的顺序依赖；而且如果保存 之前没有先固定输出选项，文件第一次被重写时长值就会被折成多行。

## 参考实现

以下基本就是 Brewin' And Chewin' 的 `BrewinConfigBootstrap` 全文：

```java
private static final ConfigUpdatePolicy CONFIG_POLICY = ConfigUpdatePolicy.builder()
        .migrate("tipsy.max-points", "tipsy.max-duration-seconds")
        .registrySection("food-effects.nourishment",
                "food-effects.comfort",
                "booze-effects.drinks",
                "tipsy.amounts",
                "tipsy.amplifiers",
                "tipsy.effects",
                "coaster.display.overrides")
        .build();

public void updateConfig() {
    ConfigUpdateReport report;
    try {
        report = ConfigFileUpdater.updateMainConfig(plugin, CONFIG_POLICY);
    } catch (Exception e) {
        warn("bac.config_update_failed", "file", "config.yml", "error", String.valueOf(e.getMessage()));
        return;
    }

    if (report.backupError() != null) {
        warn("bac.config_backup_failed", "file", "config.yml", "error", report.backupError());
    }
    for (ConfigKeyRename rename : report.migratedKeys()) {
        info("bac.config_key_migrated", "old", rename.oldPath(), "new", rename.newPath());
    }
    if (!report.retiredKeys().isEmpty()) {
        info("bac.config_keys_retired", "count", report.retiredKeys().size(),
                "keys", String.join(", ", report.retiredKeys()));
    }
    if (report.addedKeys() > 0) {
        info("bac.config_keys_merged", "count", report.addedKeys());
    }
}
```

调用位置在 `onEnable` 里，紧跟 `saveDefaultConfig()` 之后，且**在任何读取配置的代码之前**：

```java
saveDefaultConfig();
new BrewinConfigBootstrap(this).updateConfig();
// ……所有会读 getConfig() 的代码都排在这一行后面
```

## 为什么顺序是关键

**重命名必须跑在合并之前。** 当新路径已经有值时，重命名会被跳过——这是刻意的，好让服主手工改过的文件保留他写在 那里的值。但也正因如此，如果先跑合并，合并会把内置默认值种在新路径上，接着每一条重命名都变成空操作，而躺在旧 名字下的那个调好的值就被无声丢弃了。

恰好有这么一个案例。Brewin' And Chewin' 附属早期版本用 `tipsy.max-points` 表示点数计量的上限；取代它的时长模型在 `tipsy.max-duration-seconds` 下限制的是同一个量，因此服主调过的数字含义未变、应当被带过去。一旦顺序颠倒，服主 调好的上限就会被遗弃在文件里，实际生效的却是内置默认值。

**废弃键的删除夹在两者中间**——在重命名把值搬到当前路径之后、在合并写入任何东西之前，这样已经没人读的设置项就 不会再被带回来一次。

`updateMainConfig` 已经替你处理好了这个顺序。如果你自己拼这套流程，务必保持一致。

重命名**按策略列出的顺序**依次应用，因此同一趟里可以串联：先有一条把 `a` 搬到 `b`，再有一条把 `b` 搬到 `c`， 那么一份还在用 `a` 的文件会被一路带到 `c`。FarmersDelight 自己的策略就用这个特性，把好几代掉落配置名收敛到同 一个当前路径上。

重命名一个段是按键逐个在新路径重建的，因此**注释不会随重命名一起搬过去**。

## 什么是注册表段

所谓注册表段，指的是子项为**按 id 索引的内容条目**、而非固定设置项的段——一张食物表、一张酒水表、一张显示覆盖 表。区分它很重要，因为对内容而言，**删掉一个条目正是服主用来禁用该条目的手段**。若合并把条目一条条加回去，就会 在下次重启时无声地撤销服主的每一次删除，而服主根本不明白自己关掉的东西为什么又回来了。

因此合并对这类段采取"要么整段、要么不动"的策略：服主文件里完全没有这个段时才创建它；一旦文件里有了这个段，其中 的单个条目永远不再补写。

```java
ConfigUpdatePolicy.builder()
        .registrySection("food-effects.nourishment",
                         "booze-effects.drinks",
                         "tipsy.amounts")
        .build();
```

"在不在表里"本身就带有语义：在 Brewin' And Chewin' 中，一个物品出现在 `tipsy.amounts` 里才使它成其为酒，所以在 那里删掉一个 id，等于服主宣布这个物品不再让任何人喝醉。

有两个后果需要你明确接受：

* **后续版本在注册表段下新增的设置项，不会进入已有文件。** 这是"不撤销删除"所付出的代价。FarmersDelight 在 `buff.comfort`、`buff.nourishment` 这类父节点上就是刻意付这个代价的：它们形态混杂（既有设置项、又有按 id 索引 的 `foods` 表），只能在父节点这一层做保护。
* **被服主清空的列表仍算作"存在"**，因此一个值为空列表的 `trades:` 键会被原样保留，而不会被重新填满。

判定是否抑制，用的是文件**在服主留下它时**的快照，在任何写入之前拍下——否则往一个缺失的注册表段里写入的第一个 条目就会让该段"存在"，进而抑制其余所有兄弟条目，最后留下一个只填了一半的段。

## 策略 builder

```java
public static ConfigUpdatePolicy.Builder builder();

Builder migrate(String oldPath, String newPath);   // 顺序敏感，可链式调用
Builder retire(String... paths);                   // 传入 null 抛 NullPointerException
Builder registrySection(String... paths);          // 传入 null 抛 NullPointerException
ConfigUpdatePolicy build();
```

读取方法：`migrations()` → `List<ConfigKeyRename>`，`retiredKeys()` → `List<String>`，`registrySections()` → `List<String>`。策略一经 `build` 即不可变，也不持有任何针对单个文件的状态，因此一个策略可以用于你引导的任意多个 文件。FarmersDelight 就把同一个策略同时喂给 `config.yml` 和 `gui.yml`，正是为了让这两个文件在"什么算内容注册表" 这件事上永远不会走偏。

## 报告

```java
List<ConfigKeyRename> migratedKeys();  // 真正搬动过值的重命名，按应用顺序
List<String>          retiredKeys();   // 确实存在并已被删除的废弃路径，按列出顺序
int                   addedKeys();     // 合并新增的值键数量；段本身不计入
boolean               changed();       // 上述任一非空/非零时为 true
String                backupError();   // 写前备份失败的原因，成功或无需备份时为 null
```

`changed()` 是"是否要把文件写回去"的判据——`updateMainConfig` 内部就用它；如果你自己驱动各步骤，也需要它。 `addedKeys()` 只统计值键：一个含三个设置项的新段报告的是 `3`，不是 `4`。

## 自己驱动各个步骤

当目标不是插件主配置（比如 `gui.yml`、某个配方文件），或者你需要在各阶段之间插入自己的动作时，就需要这一层。 FarmersDelight 自己的 `ConfigBootstrap` 两种情况都有。

```java
public static ConfigUpdateReport applyTo(ConfigurationSection bundled,
                                         ConfigurationSection existing,
                                         ConfigUpdatePolicy policy);

public static List<ConfigKeyRename> applyMigrations(ConfigurationSection config,
                                                    List<ConfigKeyRename> migrations);
public static List<String> removeKeys(ConfigurationSection config, List<String> paths);
public static int copyMissingKeys(ConfigurationSection bundled, ConfigurationSection existing,
                                  List<String> registrySections);
```

`applyTo` 会按正确顺序跑完三个阶段并返回报告，但**不写任何文件**——两个参数都不会被保存到磁盘。报告说有改动时， 由你自己调 `tidy` 并写回。

下面是 FarmersDelight 的 `ConfigBootstrap.mergeMissingGuiKeys` 处理次级文件 `gui.yml` 时用的写法。这四个调用 里有三个声明了 `throws IOException, InvalidConfigurationException`，另外两个读操作各自还有一种「不抛异常的 失败」形态，所以下面这些防护不是摆设——去掉之后，方法既编译不过，也活不过服主还没有 `gui.yml` 的第一次启动：

```java
private void mergeMissingGuiKeys() {
    Path guiPath = plugin.getDataFolder().toPath().resolve("gui.yml");
    // readYamlFile 直接打开这个路径：文件不存在时它抛 NoSuchFileException，而不是给你一个空配置。
    // 服主还没有 gui.yml 的话，也就没有可合并进去的对象。
    if (Files.notExists(guiPath)) {
        return;
    }
    // jar 里没有这个资源时，readBundledYaml 返回的是 null，不是空配置。
    // 直接把它丢给 copyMissingKeys 会 NPE。
    YamlConfiguration bundled;
    try {
        bundled = ConfigFileUpdater.readBundledYaml(plugin, "gui.yml");
    } catch (Exception e) {
        getLogger().warning("gui.yml merge failed: " + e.getMessage());
        return;
    }
    if (bundled == null) {
        return;
    }
    try {
        YamlConfiguration existing = ConfigFileUpdater.readYamlFile(guiPath);
        int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, policy.registrySections());
        if (added > 0) {
            backupQuietly(guiPath);   // 备份失败只记日志，不中断
            ConfigFileUpdater.tidy(existing);
            ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
        }
    } catch (Exception e) {
        getLogger().warning("gui.yml merge failed: " + e.getMessage());
    }
}

private void backupQuietly(Path path) {
    try {
        ConfigFileUpdater.backup(path);
    } catch (IOException e) {
        getLogger().warning("Backup of " + path.getFileName() + " failed: " + e.getMessage());
    }
}
```

除了这些防护，还有两点值得照抄。一是 FarmersDelight 把备份放在 `added > 0` 分支**里面**，这样什么都没改的 一次启动不会往数据目录里堆 `.bak` 文件。二是它把 `backup` 包进自己的「吞掉并记日志」小工具方法，而不是让异常冒到 外层 `catch`——于是备份失败会被报告，但不会因此放弃一次本来正确的合并；这跟 `updateMainConfig` 把失败挂到 `report.backupError()` 上然后继续跑，是同一个立场。

`removeKeys` 与合并都会丢弃或重写服主写下的内容，因此手工驱动各步骤的调用方要自己负责备份：写之前先留一份。

`copyMissingKeys` 内部有一个你会继承到、且不要试图自己重写的细节：存在性判断用的是 `contains(path, true)`，它 会忽略 Bukkit 附加在 `getConfig()` 上的 jar 默认值层。若用普通的 `contains(path)`，每一个内置键都会被判定为"已 存在"，整个合并会静默地变成空操作。另外，内置文件里值为 `null` 的键会被跳过，因为写入 `null` 等于再次删除该 路径——它会在每次启动时都被算作"新增"并把文件重写一遍。

{% hint style="info" %}
添加进来的键**会**连注释一起复制；因为某个值被添加而顺带诞生的段，也会补上它的注释。
{% endhint %}

## 文件级工具方法

```java
public static void tidy(FileConfiguration configuration);
public static YamlConfiguration readBundledYaml(Plugin plugin, String resourcePath)
        throws IOException, InvalidConfigurationException;
public static YamlConfiguration readYamlFile(Path file)
        throws IOException, InvalidConfigurationException;
public static void backup(Path file) throws IOException;
public static boolean needsRestore(Path file);
public static void installBundledResource(Plugin plugin, String resourcePath, Path targetPath,
                                          boolean replace) throws IOException;
public static void writeStringAtomically(Path targetPath, String content, boolean replace)
        throws IOException;
```

**`tidy`** 必须在对配置执行 `save` / `saveToString` 之前调用。Bukkit 的 YAML 输出器默认行宽 80 字符，超过就会在 重写文件时把值折成多行——物品 id 列表、消息模板、长描述都会变成难读、手改易错的多行块。`tidy` 把行宽固定为实际 无限，缩进固定为 2 空格（与内置文件一致），并保持注释解析开启，让文件头文档和逐键注释能扛过一次读写往返。不是 `YamlConfiguration` 的对象不受影响。

**`readBundledYaml`** 在 jar 里没有该资源时返回 `null`——这是唯一不算错误的结果。内容格式错误会被抛出，好让你用 自己的措辞报告。

**`readYamlFile`** 以 UTF-8 从磁盘读取，不带 Bukkit 的 jar 默认值层，用于读取那些你没有 `getConfig()` 的文件。

**`backup`** 在原文件旁写出 `<文件名>.yyyyMMdd-HHmmss.bak`。同一秒内的两次备份会合并为一个文件，不会堆积。

**`needsRestore`** 在磁盘上的文件不可信时返回 `true`：无法作为 YAML 解析、根本读不出来、或含有 Unicode 替换 字符（这正是用错编码保存后留下的痕迹）。这与更新是两件事，`updateMainConfig` **不会**执行它。若你想获得与 FarmersDelight 相同的覆盖度，请在更新之前跑一次：

```java
if (ConfigFileUpdater.needsRestore(configPath)) {
    ConfigFileUpdater.backup(configPath);
    ConfigFileUpdater.installBundledResource(plugin, "config.yml", configPath, true);
    getLogger().warning("config.yml was unreadable and has been restored from the jar.");
}
```

**`installBundledResource`** 把 jar 内资源原子地写到磁盘。资源必须能按 UTF-8 解码，`.yml` / `.yaml` 资源还必须 能解析通过，这样一个坏掉的构建就无法用垃圾内容覆盖掉一个能用的文件。`replace = false` 时目标已存在会抛 `IOException` 而不是覆盖——这就是"缺失才安装"的形式。

**`writeStringAtomically`** 通过同目录下的临时文件加一次原子移动来写入，因此崩溃或磁盘写满时留下的是原来的完整 文件，而不是写了一半的文件。在不支持原子移动的文件系统上会退化为普通移动，父目录会按需创建。

## 启动清单

1. `saveDefaultConfig()`。
2. 可选：对不可读的文件执行 `needsRestore` → `backup` + `installBundledResource`。
3. 在 `try`/`catch` 中调用 `ConfigFileUpdater.updateMainConfig(this, policy)`。
4. 用你自己的措辞输出 `backupError()`、各条重命名、废弃键和新增数量。
5. 到这一步之后，才开始读取任何配置值。

{% hint style="warning" %}
线程：这属于启动期的文件 I/O，请在 enable 线程上、在任何代码碰配置之前跑完。它与区域线程无关，也不允许与你自己 的配置读取并发执行。
{% endhint %}

## 相关页面

* [快速上手](getting-started.md)
* [自定义 buff 与 Bossbar](buffs.md)
