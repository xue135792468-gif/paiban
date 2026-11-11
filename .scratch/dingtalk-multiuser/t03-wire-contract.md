# 03 实施契约（统筹定稿）

2026-11-11。04完整功能/定向/双轴复核放行后启动，比较基点 `dd5cba1d382b73c95c6126fa80c7f1d6861a652b`。先C绑定保护，再A免登/登录类型；B前端与C写集独立可并行。整票完成、两轴复核整改后才能进入05。

## 数据与公开协议

- V62保留给本票：`app_user.login_type`，Java `LoginType` 为 `PASSWORD` / `DINGTALK`。原账号全部PASSWORD且hash保留；DINGTALK账号hash为null，数据库与实体允许null，但密码登录/重置必须按类型拒绝，不能靠空hash偶然失败。旧构造器/fixture兼容，不能大面积删除旧断言。
- UserSummary追加 `loginType`，JSON字符串。新后端总返回有效类型，前端为既有fixture兼容可把缺失视PASSWORD；DINGTALK隐藏重置密码入口，提升角色不改类型。至少保留一个enabled PASSWORD超级管理员，按类型/身份判断，不用用户名硬编码。
- `/api/auth/dingtalk`仍只收authCode。失败JSON保留status/message，可追加稳定 `code`：`NOT_PROVISIONED`、`AMBIGUOUS_IDENTITY`、`ACCOUNT_DISABLED`、`DINGTALK_DISABLED`、`DINGTALK_CALL_FAILED`、`ADMIN_BINDING_REQUIRED`。前端按code决定说明，不依赖某一HTTP错误码猜身份。凭据/原始外部异常不得进入响应/日志/审计。
- 说明页在登录页内呈现。钉钉UA自动免登失败明确解释；手动密码入口带 `?mode=password` 并真正跳过本次自动免登，刷新也不循环。普通浏览器仍显示密码表单。SDK/配置失败也必须可读，不静默退回密码页。成功走已有登录落点逻辑。

## C：所有UID写入的保护

- 具体小模块 `DingTalkBindingPolicy`（位于identity.service），提供 `requiresExplicitFirstBinding(Teacher)`，含已有启用和停用管理员关联账号。按管理权限能力识别而非仅super_admin/admin名字：实际库可保留自定义角色，UserService也能分配它们。标量查询关联角色的管理权限集合（用户/集成/基础/学期/授课任务/规则/排课/发布/审批/设置/学校/引导/账号审计等），不把老师的全课表只读或提交申请能力当管理员。复用Permissions常量，遵循ADR-0006。
- 人工create/update教师都先拿AccountMutationGuard锚点，再读取/刷新旧教师，在任何字段变更前校验UID变化；人工变更UID需`integration:manage`。UID未传或规范化后未变的普通档案编辑不误拒；不能先修改managed字段再refresh。
- 无人值守事件拿同一锚点后匹配，不改非空UID；对未绑定但关联管理员账号的档案不自动绑定。同步的key命中/显式同名合并也不自动首次绑定受保护档案，本版统一跳过该行并给出“须超级管理员在教师档案确认”原因，避免预演宣称可填、确认却偷填。超管直接在现有教师编辑UID字段明确绑定是本版可信确认路径；不新增审批队列。
- `DingTalkMergeRules`保持纯字段规则；保护尽量在planner/应用入口统一判定，不能保留未受保护的apply分支。已有FoundationImportService教师导入UID参数是null，不虚称它有独立UID列；仍审查所有setDingtalkUserId来源及AI/导入是否会间接绕过。
- C修改AppUserRepository只新增保护存在性查询；不改UserService/AuthService/登录DTO/迁移，这些留A，避免并行冲突。C确认后A直接复用policy，不复制管理员判定。

## A：可信外部身份与短事务配对/开号

- 以 `dingtalk-official-research.md` 为主源依据：企业token保留，H5 `/topapi/v2/user/getuserinfo` body code换userid，`/topapi/v2/user/get` body userid/language查姓名。扩现有DingTalkClient与Fake Adapter，不引入OAuth扫码/通用SDK平台。真实HTTP Adapter必须有本地stub协议/坏响应/脱敏测试。
- 外部身份完成后才进独立Spring bean短事务：锚点→锁后教师/账号查询刷新，UID优先；重复UID安全拒绝。同名候选严格按原规格在“未绑定UID且未软删”的档案中计数；已绑定另一UID者按规格不属于该集合。不得按active或管理员保护标记进一步过滤后假称唯一：先计数，再对唯一候选检查保护。锁后重新查询，不能沿用竞争绑定前的旧候选。Teacher.active不替代account.enabled。
- C的受保护档案唯一同名也返回ADMIN_BINDING_REQUIRED，普通教师按原规格自动配对。仅停用账号拒绝且不留下配对，不建影子账号；有多个启用历史账号按稳定ID复用，不能任意返回顺序。两个UID抢同一教师不得覆盖先到者。
- 自动账号名称≤64且不会碰撞后复用不相关账号；锚点内检查/生成，并发同UID只一号。账号创建+绑定+各自审计同事务；使用现有AccountAuditService，操作者“系统（钉钉免登）”，before/after严格allowlist，可追加登录类型/绑定UID业务字段，绝不放凭据/hash。
- 审计快照具体追加 `loginType`、`dingtalkUserId`，让BIND前后能看到UID差异；旧JSON缺失字段保持null（未知），不反推或改写历史记录。旧6参快照构造器兼容，自动新快照由当前真实账号/教师生成。
- 真实外部异常映射固定安全code/message；不保留跨网络事务。AuthProvider既有Optional形状可保留成功接口，用专用异常表达失败；结构化异常可在集成Controller专用handler处理，避免污染所有既有API错误协议。

## B：前端与浏览器准备

- 独立写集：LoginView及新composable/说明组件与组件测试、frontend auth/api类型、UsersView隐藏密码按钮、TeachersView按integration:manage限制UID编辑（只读可见；非超管提交普通编辑时省略UID字段）、对应测试和一条fake免登/失败回退Playwright。
- 后端未合入前B只跑定向组件/类型/lint；实际Playwright由主线程在A/C整合后串行跑。现有按钮/路由模式复用，不重做登录页视觉，不引入Hark。

所有子任务使用自己的worktree，先核系统资源；机械探查仅Gemini3.8，实际施工DeepSeek high，B由Gemini4。定向验证与真实退出码、日志、scope内清理；最后完整verify仍留到全部八票收尾。
