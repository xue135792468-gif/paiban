# 票04 同步预演停用代码定位图（统筹核实版）

基点4eb1886，2026-11-11。Gemini初稿的DTO名、Fake路径及多处行号有误，以下按实际源码重新核实；未执行04测试，不代表功能完成。

## 已核实源码

- `integration/org/DingTalkDirectory.java:15`：users/departments/skipped/failedDepartments；failedDepartments来自Loader的失败调用计数，不是一个现成的complete布尔值。
- `DingTalkDirectoryLoader.java:171`：loadUsers分页；不可读部门在189行返回unavailable=true，137–140行累加failedCalls。并发fetch在218–225行对不可用分支降级计数，其余异常中止。
- **真实待修风险**：Loader182–184行在hasMore=true但nextCursor==cursor时返回unavailable=false，把截断目录当完整；还需防循环游标。04新增缺失停用前必须将这种扫描标为不完整，并用fake分页HTTP/集成行为证明不产生停用候选、不执行停用。
- **HTTP坏响应风险（396bd8b源码核实）**：`HttpDingTalkClient.listUsers`遇null响应或缺少result时返回合法末页空列表，`listSubDepartments`缺result也返回空列表，可能把协议失败当完整空目录。04须区分合法空list与缺少必要结构，后者失败关闭，绝不据此生成/执行缺失停用；用真实HTTP adapter的本地stub契约验证，不能仅靠Fake的非零errcode场景。
- `DingTalkOrgDataProvider.java:82`：preview读取配置/教师、拉目录后规划，不写DB或审计。`confirm:128`在事务外拉网络，146行transactionTemplate只包写入；`sync:77`委托confirm(none)。不需要为了短事务再造一套同步框架。
- `DingTalkOrgDataProvider.java:167`：apply只创建/补齐Teacher，未操作AppUser；已有deactivated是远端inactive造成的教师档案计数，不是缺失账号停用数，不能无说明混用。
- 同步异常已经使用`DingTalkApiException.redact(ex.getMessage())`（Provider91、138行）；redactor64行会遮掉access_token URL值。后续新错误路径应复用/验证，不要宣称此处未脱敏或另造重复器。
- `DingTalkSyncDtos.java:59`实际名是ConfirmRequest，含excludedDepartments/excludedUserIds/suspectedDecisions；不是ConfirmSyncRequest。领域选择在`DingTalkSyncDecisions.java:12`，疑似同名动作MERGE/CREATE/SKIP；没有显式停用账号ID集合。
- 当前`applyEvent`在Provider215行@Transactional；238行可写UID，user_leave只设Teacher.active=false。Controller183行事件入口检查集成启用且非空事件token，恒定时间比较；不是未认证的空token入口。
- 账号停用走`identity/service/UserService.disable`，与update/resetPassword/enable共用super_admin锚点+刷新；具体行号随02整改变化，以方法为准。04写入应与03统一锁序。

## 前端与测试入口

- `frontend/src/views/SettingsView.vue:86`挂载`components/DingTalkSyncPreview.vue`；工具栏为`components/settings/DingtalkIntegrationActions.vue`。当前没有将停用分类/选择。
- Fake位于**main源码** `backend/src/main/java/com/paiban/integration/dingtalk/FakeDingTalkClient.java`，不是test路径。
- `backend/src/test/java/com/paiban/integration/org/DingTalkDirectoryLoaderTest.java:111`为skipsAnUnreadableDepartmentButKeepsTheRest，116行failUserList模拟局部不可读。
- `backend/src/test/java/com/paiban/integration/DingTalkIntegrationTest.java`覆盖预演/确认和事件，是后续HTTP入口。以实际测试方法为准，不沿用手填旧行号。

## 统筹验收重点（非04施工）

- 缺失判断使用**完整原始扫描UID集合**，不能用按部门/人员排除后的导入计划来制造“缺失”；仍在目录但本次不导入的人不能被停用。
- 预演只读，无账号/教师/审计写入；只有显式选择的稳定账号ID才可在完整重扫后停用。none/旧sync/事件不得因默认值变成全选停用。
- 目录完整后，确认按统一账号锁序重新读取本地状态/规划；外部网络不在锚点锁内。部分扫描或坏游标时，旧预演的选择也不能触发停用。
- 稳定账号ID防止扩批，但预演后该账号改绑另一教师/UID且新关联也缺失时，仅ID不能识别漂移。确认载荷可携带最小teacherId/UID期望值供锁后对照；不需要写预演会话表，也不能信客户端自称missing，仍须完整新扫描与新鲜候选复核。
- 新增账号停用数要与既有Teacher.active指标区分；缺失停用只改账号，不删除档案或改课表。逐账号审计复用02入口，JWT下一请求拒绝；最后超管保护保留。
- 身份绑定保护现已获用户明确确认，03将收口自动事件/同步，防止绕过“已有高权限账号首次可信绑定”的规则；04本票的扫描/停用实现不代表该03保护已经完成。
