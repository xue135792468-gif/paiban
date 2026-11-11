# 票05 复查整改交付：E2E 夹具调用与无学期文案（2026-11-12）

分支 `codex/dingtalk-t05-review-fixes`，起点为集成提交
`dd4ebe7d31eb8d9a9d7777b989e7d5c25b3d5aa7`。本次只处理已确认的两条复查项，
不改前四票，也不宣称票 05 已放行。

## 已确认问题与整改

- 主控对 candidate `661b3` 实跑完整 6 条 Playwright，得到 4 passed / 2 failed、exit 1。
  两个失败的根因相同：`submitCover` 把 Playwright `Page` 传给只接受 `Locator` 的
  `pickOption`，在 `scope.page()` 处抛 `TypeError`，尚未进入审批。
- E2E 现将“期望代课”表单项的 `.el-form-item` Locator 传给既有 `pickOption` helper。
  `data-testid="expected-substitute"` 位于该表单项内，选择器和原有 helper 均未改变。
- 增加两处真实状态截图，复用 `frontend/e2e/screenshots.ts` 的 `capture`：
  `t05-review-fixes-approval-changed-teacher.png` 在改人批准对话框中选好教师后截取；
  `t05-review-fixes-teacher-effective-expected-actual.png` 在教师看到“已生效”并核对
  期望人与实际人后截取。截图只作人工证据，不承担断言。
- 无学期状态文案改为：“请由教务管理员在「学期管理」中创建并设置当前学期，之后这里会
  显示代课申请与审批记录。”现有标题、`data-testid`、样式和权限逻辑保持不变。

## 主控视觉 QA

主控实际执行 `view_image` 查看首轮成功的无学期截图，确认教师身份下原文案“请先在
「学期管理」中创建并设置当前学期”会引导教师进入其无权操作的管理页面。本次按主控确认
改为明确由教务管理员完成创建和设置。后续两轴固定 `661` 源码判断仍由主控执行。

## 红 → 绿证据

| 阶段 | 证据 | 结果 |
|---|---|---|
| 真实红（主控） | `E:/排课系统/.scratch/dingtalk-multiuser/logs/t05-browser-run1/playwright.log` 与 `E:/排课系统/runtime/t05-browser-run1/test-results/` 下两份 approval error-context | 完整 6 条 Playwright：4 passed / 2 failed，exit 1；源码一致标记 `SOURCE_IDENTICAL=1`，服务均退出 |
| 本次验证 | 对 Vue 与 E2E 两个文件一起运行定向 ESLint；原始日志为 `E:/排课系统/.worktrees/dingtalk-t05-review-fixes/.runtime/logs/t05-targeted-eslint.log` | exit 0 |

本轮没有伪造单测或镜像测试。修复后的完整 6 条 Playwright 由主控统一复跑，本工作树按
授权不自行启动服务、网络或 Playwright。

## 验证边界

- 仅对 `frontend/src/views/SubstitutionsView.vue` 与
  `frontend/e2e/substitution-approval.spec.ts` 一起执行定向 ESLint。
- 未运行 Vitest、后端测试、Playwright、全量 `verify.sh`、服务或网络。
- `vue-tsc -b` 不覆盖 E2E，不能作为两处失败调用已通过类型检查的证据。
- 临时 JSON、ESLint 日志与缓存均置于本工作树 `.runtime/`；shell、Git hook 和 npm cache
  均显式指向该目录。
- 不处理 reviewer 报告中的其他判断项；收到主控后续确认的整改清单前不扩写集。
