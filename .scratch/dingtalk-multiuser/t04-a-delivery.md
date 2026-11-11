# 票04-A 交付：只读预演与扫描完整性（2026-11-11）

分支 `codex/dingtalk-t04`，基点 1591071（已合并集成）。只做 A 切片：预演追加「将停用」候选与
扫描完整性；不做确认停用、不动 identity 写保护、不做前端。

## 做了什么

- 预演响应追加（既有字段不变）：`scanComplete`、`deactivationWarning`、`deactivations[]`，
  候选字段 `accountId/accountName/displayName/teacherId/teacherName/dingtalkUserId`。
- 候选 = 教师档案有 `dingtalkUserId`、账号 `enabled=true`、且该 UID 不在本次**完整原始目录**里；
  按 `accountId` 升序。排除部门 / 排除用户只影响导入，不影响原始 UID 可见集合；无教师或已删除教师
  的账号不参与。`Teacher.active` 不参与判断。
- 扫描不完整（部门局部失败、坏游标/环、HTTP adapter 缺 `result/list` 结构）→ `scanComplete=false`、
  候选空、给中文警告；预演失败响应同样 `scanComplete=false`。合法空列表仍可用。
- `DingTalkDirectory` 增加 `incompleteReason/complete()`；Loader 把「hasMore 但游标不推进/回环」标为
  不完整；`HttpDingTalkClient` 对缺结构与 hasMore 无 cursor 抛 `DingTalkApiException.incomplete`，
  并提供可注入 RestClient 的测试构造器。
- `FakeDingTalkClient.stuckUserListCursor(dept)` 供离线模拟坏分页。预演路径仍严格零 DB/审计写。

## 红 → 绿

- 红：`DingTalkSyncPreviewDeactivationIntegrationTest` 编译失败，缺 `DingTalkApiException.incomplete`
  与 `FakeDingTalkClient.stuckUserListCursor`（旧预演也没有候选字段）。日志 `logs/a-red.log`，
  SHA256 `6A493C1ACA0ED0D297563169009B67A6DD674680B387C8CC3A747015D0A15415`，exit 1。
- 绿：`./mvnw -B -DforkCount=0 -Dtest="DingTalkSyncPreviewDeactivationIntegrationTest,HttpDingTalkClientTest,DingTalkDirectoryLoaderTest,DingTalkIntegrationTest" test`
  → **48 通过 / 0 失败，BUILD SUCCESS，exit 0**：
  - 预演候选与完整性 6（完整缺失候选/零写/排除/局部失败/坏游标/协议失败）
  - HTTP adapter 5（缺 result/list、hasMore 无 cursor、缺 result 子部门、合法空页）
  - Loader 14、既有预演/确认 23
  日志 `logs/a-targeted.log`，SHA256 `B1B44D179EEC2F17F0E5A99DD7E281079680D96528CD84130E75847CCBEAE1F0`。

## 边界与未验证

- 未实现确认停用（C）与前端选择（B）；`ConfirmRequest` 未加 `deactivationSelections`，任何 confirm
  都不会停用账号。`OrgSyncResult.accountDisabled` 待 C。
- 编辑 / 停用漂移、self/最后超管、JWT 下一请求 401 等属于 C 的验收，本片未覆盖。
- 仅 H2（test profile + 假钉钉）；无 Docker / 真实 PostgreSQL / 真钉钉，未跑全量 verify、未推送部署。
