# 来源与授权

本仓库（FarmersDelight）采用 **GPL-3.0**（GNU General Public License version 3）授权，全文见 [LICENSE](LICENSE)。

允许使用、修改、再分发，也允许收费分发；但再分发时必须满足 GPL-3.0 的条件：保留版权与授权声明、
附带完整对应源码、修改过的版本同样以 GPL-3.0 授权。以 jar 形式散发的修改版也必须提供源码。

## 第三方内容

插件自身的代码由本仓库以 GPL-3.0 发布。它同时搬运了同样以自由许可证发布的第三方内容，这些内容的
版权仍归原作者，并按各自的许可证分发：

| 内容 | 来源 | 作者 | 许可证 |
|---|---|---|---|
| 贴图、配方、数值、游戏行为 | Farmer's Delight（Minecraft 模组） | vectorwing | MIT |
| AntiGriefLib（shade 进 jar，重定位到 `com.huidu.farmersdelight.libs`） | AntiGriefLib | XiaoMoMi | MIT |
| bStats（shade 进 jar，重定位到 `com.huidu.farmersdelight.libs`） | bStats | Bastian Oppermann | MIT |

MIT 与 GPL-3.0 兼容，第三方内容的 MIT 声明随 jar 分发，完整文本见
`src/main/resources/NOTICE.txt`（也就是打进发布 jar 的那一份）。

## 运行时依赖

以下依赖不打进 jar，只在运行时调用，因此不随本仓库分发其授权文本：

- CraftEngine（GPL-3.0），内容平台与配方数据来源
- UltimateAdvancementAPI，成就系统；本仓库带了一份 fork，见 `libs/` 与
  [UltimateAdvancementAPI](https://github.com/IOVEYOUMC0/UltimateAdvancementAPI)

## 商业分发

GPL-3.0 不禁止收费。付费版与公开源码版的区别只是构建开关（付费版含额外的编辑器、手持烹饪等实现，
源码不公开分发），这不影响 GPL-3.0 对公开源码版授予的权利，也不影响公开源码版源码的完整性要求。

## 关于盗版分发

如果有人拿到本插件的构建产物或源码后去掉版权声明、自行"开源"或加入后门代码再分发，那属于违反
GPL-3.0 的行为，与作者无关。作者只对本仓库（以及作者本人发布的构建产物）负责。
