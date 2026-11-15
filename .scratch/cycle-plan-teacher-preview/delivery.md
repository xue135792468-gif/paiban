# 教师课表预览实现交付

用户接受原型 75961c6 后，正式施工保存在 codex/cycle-teacher-preview。基点 c27af84f449df5d572d6f2bdd9757799652e43c9，首轮源码 0e625e9d7db4706bbdda4c90996a259927772e18；整改后的最终产品源码为 0502c137fe7997c4fd6f40b698c4ab6b5d6f8488。最终 PR 提交为 `91656df45943fca619caf76ed529d5c4754765de`，已推送并创建 [PR #32](https://github.com/xue135792468-gif/paiban/pull/32)；尚未合并或部署。

## 实现

- CyclePlanPanel 的教师姓名可打开预览；桌面并排，窄屏全屏；返回恢复原方案列表位置。
- CyclePlanTeacherPreview 复用 DraftGrid，显示当前草稿中教师跨班完整周课表；按教师 ID 切换。
- 默认应用后且明确尚未生效，可切当前安排。cyclePlanPreview 以不修改输入的纯投影同时处理整组移动，显示受影响课节及来源/目标位置。
- 草稿编辑器传入真实课程信息；预览不请求业务写接口，原 apply/cancel 事件及确认链路保持使用。
- DraftGrid 增加可选只读注释和移走位置说明；帮助文档同步更新。既有 E2E 两个按钮选择器改为专用 apply 标识。
- 无后端、迁移、权限、求解器或依赖变更。

## 实际检查

Qwen 执行，主控未复跑前端检查；最终 lint/build/unit 的 native 输出已核对真实退出结果。

| 检查 | 结果 |
|---|---|
| vue-tsc --noEmit | 退出 0 |
| 相关定向 Vitest | 首轮 44 通过；整改后 49 通过，退出 0 |
| npm run lint | 退出 0 |
| npm run build | 退出 0 |
| VITEST_MAX_WORKERS=4 npx vitest run | 首轮 107 文件、821 单测；整改最终 107 文件、826 单测通过，退出 0 |
| SFC、票状态、E2E helper 去重、模块边界、迁移、本地日期检查 | worker 报告各退出 0；提交钩子的仓库检查也正常执行 |
| doctor.sh | worker 报告退出 2，本机无 Docker；不计为检查通过 |

## 验证边界

2026-11-15 已由 worker 在 Chromium 实际执行 `bash scripts/e2e.sh e2e/draft-drag.spec.ts -g "联动方案点教师姓名看课表"` 两次，均 **1 passed / 0 failed / 退出 0**；分别耗时 1.1 分钟、34.2 秒，使用隔离端口和 H2 内存库。用例覆盖点姓名、默认应用后、切当前安排、换教师、桌面关闭、390×844 窄屏返回与零写请求，并复核草稿未变。主控核对原始日志及退出码；完整日志保留在本工作树 `.runtime/cycle-teacher-preview/run1.log`、`run2.log`。没有修改断言、超时或跳过用例。

目标用例的桌面/手机尺寸浏览器证据不等于手机真机或视觉观感验收；全量 Playwright 另由下列冻结门禁覆盖。E2E 不在 tsconfig.app 覆盖内，显式 ESLint 探查曾输出布尔 True 而非可绑定的数字退出码，因此不额外宣称其已获独立静态门禁证明。

## 2026-11-15 冻结完整门禁

`VITEST_MAX_WORKERS=4 bash scripts/verify-run.sh cycle-teacher-preview-final --full` **退出 0**，绑定 `587184c407968fb78b9c467439a480116b5c2d50`；实际命令 `bash scripts/verify.sh --full`，从 11:25:33 至 12:12:01（本地时间）。

- 前端 lint、build、typecheck、仓库级检查均通过；Vitest **107 文件 / 826 项通过**。
- 后端 `mvnw verify` **1265 项，0 失败 / 0 错误 / 7 跳过**，`BUILD SUCCESS`。本机无 Docker，真实 PostgreSQL 容器测试按既有规则留给 CI；未声称 `--deploy` 已跑。
- Playwright **156 项通过，0 失败**，耗时 18.7 分钟。
- 收据 `cycle-teacher-preview-final`：HEAD 前后一致；源范围 1079 个 tracked 文件前后字节一致，drift/missing/untracked 均 0；归档有效，工具退出 0。
- 原始日志留在 `.runtime/verification/cycle-teacher-preview-final/verify.log`；归档收据在 `.scratch/verification/cycle-teacher-preview-final/`。

PR #32 首轮 CI run `43676022144` 的 backend / frontend / deploy-scripts 均通过，其中真实 PostgreSQL `PostgresContainerIT` **1/1 通过**；E2E 155 通过、1 失败，失败为 `lifecycle.spec.ts` 的同名按钮定位歧义，本功能用例已通过。主控将旧用例定位限定到 `.toolbar`，保留停用教师不可选的原断言；定向重复运行 4/4 通过，双轴复核均无问题，细节见 review.md。

随后对修正提交 `cdd2b7ec97696bc116c51a7034d16a30cace7969` 再跑 `verify-run cycle-teacher-preview-ci-fix --full`：**退出 0**，前端 826、后端 1265（7 跳过）、Playwright 156 通过；源码前后一致、收据有效。最新原始日志在 `.runtime/verification/cycle-teacher-preview-ci-fix/verify.log`，归档在 `.scratch/verification/cycle-teacher-preview-ci-fix/`。两份收据均保留，不用较早版本冒充最新版本。

最终 PR run `43681565888` 在 `91656df45943fca619caf76ed529d5c4754765de` 上 **backend / frontend / e2e / deploy-scripts 四 job 全部通过**，PR 为 OPEN 且可合并。未自动合并、部署或操作生产数据；手机真机和最终视觉观感仍由用户验收。

worker 报告临时调试 spec 已删除，无本轮启动的常驻进程；忽略的 frontend/dist 为构建产物，保留。主仓库原有 AGENTS.md 与其他任务现场未纳入本分支。

## 代码复查

对固定 c27af84...0e625e9 执行一次独立 Standards/Spec 双轴只读复查。四项确认问题统一交 Qwen 整改：实际列表滚动容器、当前教师空格占用口径、失配时整组不投影、提供新交互 Playwright 用例。整改后只核相关源码增量，未重开整项目复查；定向 49、lint/build/typecheck 和最终全量前端 826 均通过，原始记录与处置见 review.md。

2026-11-15 对 c27af84...0502c13 再执行独立双轴增量复审：Standards 硬性违反 0、判断建议 5；Spec 缺失/范围扩张/逻辑错误均 0。主控只同步一处整组不投影的帮助文案，没有新增业务行为。判断建议不在本票扩大重构；详见 review.md。

最终只读投影在缺课/来源或教师身份失配/目标节次缺失/移动课锁定时全组不可用，显示当前课表并禁用应用后预览；不复制服务端全量约束、版本、权限或指纹规则，真实 apply 继续由原后端判定。新增组件测试验证本地状态及渲染，不当作手机 CSS/滚动实机证明。

模型档位说明：初次施工省略档位，宿主记录为 max；随后工具明确 group/qwen3.8 只支持 max/ultra。复查显式使用该组较低档 max。底层 dumate 模型目录的 high 与 routing group 的档位不是同一配置，不能据此推断服务端实际推理强度或模型能力。

整改由明确指定 group/qwen3.8/max 的 Dalton 完成；原施工代理恢复时变成 GPT，已在无源码改动时关闭。整改者报告临时 frontend/.tmp-codex 脚本已删除、无本轮常驻进程；最终 git status 也未见这些临时文件。
