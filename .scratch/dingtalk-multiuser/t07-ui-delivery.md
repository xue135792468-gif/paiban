# 票 07 前端交付切片：设置页测试连接

2026-11-13（Asia/Shanghai），worktree
`E:/排课系统/.worktrees/dingtalk-t07-ui`，分支
`codex/dingtalk-t07-ui`。

- 开工基点：`877dee9`。
- 收尾合并：fast-forward 到 `codex/dingtalk-multiuser` 的
  `9d6ea2b`，未 reset 任何未提交文件。
- 本切片只写前端、专用 E2E 与本记录；未改 backend、共享
  `frontend/e2e/helpers.ts`、生产 API/types、spec、票 Status、HANDOFF、
  execution、公共协调文档或手册资产。

## 行为

- 新增 `DingtalkConnectionProbe.vue`，设置页用它渲染 AppKey/AppSecret、
  独立“测试连接”按钮和结果。
- 测试始终发送当前未保存表单快照：
  `POST /api/integrations/dingtalk/test-connection`
  `{appKey, appSecret, useSavedSecret}`。不要求集成 enabled，不保存、不清空、
  不回填，也不改变保存状态。
- 有已保存 Secret、当前 AppKey 与保存值一致且 Secret 留空时，提示并发送
  `useSavedSecret=true`；改 AppKey 后留空则要求新 Secret，按钮不可用。
- 保存仍沿用既有“空 Secret 不覆盖”payload 语义。
- 探测使用独立 `testing` 状态；测试中保存按钮和页面其它功能不进入同一 loading。
- 输入变化立即清结果。每次请求有 generation 快照，输入在请求中变化后，晚返回的
  旧结果不会成为当前参数的绿色成功。
- 成功文案由 UI 固定为“当前凭据可获取访问令牌并读取根部门”，不宣称通知、手机、
  CorpId、AgentId 或完整可见范围已验证。
- 200 失败显示服务端固定安全中文；Axios/网络/HTTP 错误只显示固定中文，不渲染
  raw Axios 异常或供应商正文。
- 保留既有 `canManageIntegration` 卡片可见性、保存、清除、同步预演和 06 通知
  发送记录入口。

## 测试 seam 与覆盖

- 组件公开行为：`frontend/src/components/settings/DingtalkConnectionProbe.spec.ts`
  覆盖当前未保存参数、保存 Secret 复用、改 key 后要求新 Secret、精确成功文案、
  固定安全失败、输入清结果、晚返回失效、busy 防重复。
- 设置页公开行为：`frontend/src/views/SettingsView.spec.ts`
  覆盖教务/老师隐藏且不请求、超级管理员可见、测试不保存/不清除、测试中保存仍可用、
  既有空 Secret 保存 payload 不变。
- E2E：`frontend/e2e/dingtalk-connection.spec.ts`（新，已写未启动）
  使用真实前端控制器与 E2E Fake backend POST，不 route 伪造成功，不访问真实 tenant。
  两次 probe 都断言 HTTP 200、JSON `success=true` 且 `code=OK`；两用例的主 page、
  teacherPage 都监听并断言 console error/pageerror 为空。
- 截图前对连接结果执行 `scrollIntoViewIfNeeded()`。主控真实运行后的默认截图名为
  `t07-connection-unsaved.png`；该图将只是本地 Fake 演示。

推荐主控真实运行的 E2E 用例：

1. 未保存参数：`unsaved-e2e-key` + 新 Secret → 成功只说明 token + 根部门；
   API 回读仍保留旧 AppKey/hasSecret。
2. 已保存复用：AppKey 未改、Secret 留空 → payload `useSavedSecret=true`；改 key
   后留空 → 按钮禁用。
3. 教务管理员和老师：页面无 `#dingtalk-integration` /
   `[data-testid=dingtalk-probe]`，页面未发 probe；教务直接调用接口为 403。

稳定 testid：

- `dingtalk-connection-probe`
- `dingtalk-probe-app-key`
- `dingtalk-probe-app-secret`
- `dingtalk-probe`
- `dingtalk-probe-result`

## 纵向红绿证据

所有 Vitest 均在本 worktree 的 `frontend/` 执行：
`npx vitest run --no-file-parallelism <文件>`。所有日志保留在
`E:/排课系统/.worktrees/dingtalk-t07-ui/.runtime/`。

| 循环 | 阶段 | 实际退出码 / 结果 | 日志 |
|---|---|---:|---|
| 未保存参数 | 红 | `1`；`httpMock.post` 0 calls | `.runtime/red-01.log` |
| 未保存参数 | 绿 | `0`；1/1 | `.runtime/green-01.log` |
| 保存 Secret 复用 | 红 | `1`；缺少复用提示 | `.runtime/red-02.log` |
| 保存 Secret 复用 | 绿 | `0`；2/2 | `.runtime/green-02.log` |
| 改 key 空 Secret | 红 | `1`；缺少新 Secret 提示 | `.runtime/red-03.log` |
| 改 key 空 Secret | 绿 | `0`；3/3 | `.runtime/green-03.log` |
| Axios 安全失败 | 红 | `1`；无结果且 unhandled rejection | `.runtime/red-04.log` |
| Axios 安全失败 | 绿 | `0`；4/4 | `.runtime/green-04.log` |
| 输入变化清结果 | 红 | `1`；旧结果仍存在 | `.runtime/red-05.log` |
| 输入变化清结果 | 绿 | `0`；5/5 | `.runtime/green-05.log` |
| 晚返回失效 | 红 | `1`；旧请求结果被显示 | `.runtime/red-06.log` |
| 晚返回失效 | 绿 | `0`；6/6 | `.runtime/green-06.log` |
| busy 防重复 | 已有绿回归 | `0`；7/7；原 Element Plus loading 已阻止第二次点击，未强造红 | `.runtime/red-07.log` |
| 设置页边界回归 | 绿 | `0`；2 files / 18 passed（主控已对照原始日志纠正，不计为19） | `.runtime/green-08.log` |
| 成功不得扩大验收声明 | 红 | `1`；显示服务端夸大成功文案 | `.runtime/red-09.log` |
| 成功不得扩大验收声明 | 绿 | `0`；8/8 | `.runtime/green-09.log` |

主控已读的最终组件定向批次：

- `.runtime/final-vitest-2.log`：主控观察到 `20 passed`，真实退出码 `0`。
- `.runtime/merged-vitest.log`：合并 `9d6ea2b` 后 `20 passed`，真实退出码 `0`，
  before/after 目标清单 SHA-256 与内容完全一致。
- 后续 TypeScript 修正只把页面局部 `appSecret` 明确为 string-backed。

早期 `.runtime/final-vitest.log` 为测试 harness 自身使用了 Vitest 不识别的
`toHaveText` matcher，退出码 `1`；修正断言 API 后按主控要求未重复全部组件批次。

## 静态验证与真实 host 退出码

- `.runtime/final-eslint.log`：目标 5 个源/测试文件，真实退出码 `0`。
- `.runtime/final-vue-tsc.log`：真实退出码 `2`，发现 `appSecret?: string` 与组件
  string 双向绑定不符。
- `.runtime/final-vue-tsc-2.log`：最小类型修正后真实退出码 `0`。
- `.runtime/final-eslint-2.log`：最终 SettingsView + E2E，真实退出码 `0`。
- `.runtime/final-e2e-tsc.log`：专用 E2E TypeScript 检查，真实退出码 `0`。

最终源 before/after 清单不是 `git ls-files`，而是递归枚举
`frontend/src/**` 与 `frontend/e2e/**` 的 365 个现有文件，包含全部新未跟踪组件和
E2E：

- `.runtime/e2e-tsc-before.sha256`
- `.runtime/e2e-tsc-after.sha256`
- 两次 manifest 文件 SHA-256 均为
  `9509A7D51AD625E14BE7B70F31F14A5EE5A1ED37A02674797B5D46753AFF01B7`
- `SOURCE_HASH_MATCH=True`。

最终目标文件 SHA-256：

```text
5BE886F9FAA0061791AC3AEA09AF5457CFA40A41B0197873B63E0EDC331C18A3  frontend/src/components/settings/DingtalkConnectionProbe.vue
011F0F46C12AA47B99464DDC6AA3DA026918C0617E4EB3D8397FAEED5F05F700  frontend/src/components/settings/DingtalkConnectionProbe.spec.ts
BE633248EEAE6C29A343D24DE79B234349B56169D6500BA0000BDB97F6A9D64E  frontend/src/views/SettingsView.vue
83B1BBE4BB6A787BC858756DB605965BCFA020E24C1C4E0937DECA94C55EFED4  frontend/src/views/SettingsView.spec.ts
E57D5729AF2F3AFA6417A9BB9DD924D5C5DB196F95487A253C646B7EE8B8B9AE  frontend/e2e/dingtalk-connection.spec.ts
```

## 改动文件

- `frontend/src/components/settings/DingtalkConnectionProbe.vue`（新）
- `frontend/src/components/settings/DingtalkConnectionProbe.spec.ts`（新）
- `frontend/src/views/SettingsView.vue`
- `frontend/src/views/SettingsView.spec.ts`
- `frontend/e2e/dingtalk-connection.spec.ts`（新）
- `.scratch/dingtalk-multiuser/t07-ui-delivery.md`（本文件）

## 未验证 / 边界

- 按指令未启动 Playwright/E2E、后端、前端、真实 PostgreSQL、Docker、部署或
  真实钉钉 tenant；未运行 full verify，未 push。
- 未生成截图、未写 docs/manual 资产。主控需在后端切片合并后真实运行 E2E，
  检查 console/pageerror 为空并 `view_image` 本系统截图；默认文件名
  `t07-connection-unsaved.png`。
- 本地 Fake 证明接线、交互和只读边界，不证明真实 token、根部门权限、H5、AgentId、
  CorpId、手机通知或全部可见范围。
- 后端 HTTP 成功/失败码、无数据库副作用与真实供应商请求契约由 07 backend 切片
  及其主控双轴复核证明。
