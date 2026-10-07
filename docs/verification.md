# 实机与构建验证矩阵

本文件把"平台 × CraftEngine 版本 × 验证项"的结论集中在一处，每条都注明**证据来源**（测试全名 / 文档路径 / 实机记录）与**日期**。

口径：

- `结论` 只允许 `通过` / `未验证` / `不适用` 三种。
- `通过` 必须给出可核查的证据：`test:<测试类全名>`（该类必须存在于 `src/test/java`）或 `doc:<仓库内相对路径>`（该文件必须存在）。实机结论若只有口头记录，记到 `docs/` 或 `scratchpad/team-out/` 后再引用。
- 没有验证过的一律写 `未验证`，证据栏写 `-` 或 `manual:<待做事项>`，并由 `tools/check_verification_matrix.py` 汇总成清单。

| 平台 | 版本 | CE 版本 | 验证项 | 结论 | 证据来源 | 日期 |
| --- | --- | --- | --- | --- | --- | --- |
| Paper | 1.21.5 | 26.9.1 | 分片注册只从轮次末尾发布（多来源共一轮、超预算全量可匹配） | 通过 | test:com.huidu.farmersdelight.recipe.RecipeRegistrationDriverTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 第一次装载（> 预算也）整批内联发布：启动时 manager 条数不为 0 | 通过 | test:com.huidu.farmersdelight.recipe.ColdRecipeLoadTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 分片预算：文件 ≤ 预算时不 arm 任务、超预算按预算推进 | 通过 | test:com.huidu.farmersdelight.recipe.RecipeRegistrationBudgetTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 预热与内容摘要在配方发布之后才跑（R1 契约，含趟代次守卫与失败跳过） | 通过 | test:com.huidu.farmersdelight.recipe.RecipePublicationWiringTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 发布失败时回滚 pot + 砧板 + 派生索引，保留上一版目录 | 通过 | test:com.huidu.farmersdelight.recipe.RecipePublicationRollbackIntegrationTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 命令与编辑器同时接受裸 id 与带命名空间 id | 通过 | test:com.huidu.farmersdelight.recipe.RecipeIdWiringTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 编辑器写 YAML：保留其余部分逐字节、原子替换、失败不半写、同文件不交错 | 通过 | test:com.huidu.farmersdelight.util.yaml.YamlFileTransactionTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 编辑器写盘离 owner 线程、完成回 owner、满队列显式失败、停机记录在途 | 通过 | test:com.huidu.farmersdelight.gui.editor.EditorWriteQueueTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 配方解析：同内容代次复用、换代与换内容都不复用、有界 FIFO、并发安全 | 通过 | test:com.huidu.farmersdelight.recipe.ParsedRecipeCacheTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 解析缓存接线：两 manager 的解析都经缓存、协调器三趟推进内容代次 | 通过 | test:com.huidu.farmersdelight.recipe.RecipeParseCacheWiringTest | 2026-10-07 |
| Folia | 1.21.5 | 26.9.1 | 跨 region 邻块写入前判定归属，不越界写 | 通过 | test:com.huidu.farmersdelight.block.behavior.FoliaNeighborWriteTest | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 整树构建与测试（163 suites / 863 tests / 0 failures / 0 errors / 0 skipped） | 通过 | doc:scratchpad/team-out/r4-r6-result.md | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 启动实机：内容摘要行的厨锅/砧板数字与 `recipes/*.yml` 条数一致 | 未验证 | manual:业主测试服重启后核对那一行 | 2026-10-07 |
| Folia | 1.21.5 | 26.9.1 | 编辑器实机：保存一条配方后手写注释仍在原处 | 未验证 | - | 2026-10-07 |
| Paper | 1.21.5 | 26.9.1 | 停服实机：仍有在途编辑时出现 `recipe_saves_pending` 告警 | 未验证 | - | 2026-10-07 |
| Paper | 1.21.5 | 26.9.2 | 与 26.9.1 同口径的构建与测试 | 未验证 | - | 2026-10-07 |
