# 05 审批事务边界与权限方案（准备，尚未施工）

2026-11-11，统筹按 396bd8b 实读 SubstitutionRequestService 后补充，和 `t05-code-map.md` 配套。后续以实际源码、规格和测试为准，不把本文件当完成证据。

## 具体模块边界

- 保留 create 的公开入口与既有 PlanSnapshot/PlanVerifier/SubstitutionEffects，移除提交末尾的立即生效段。提交只保存 SUBMITTED 和一次历史；保存实际 operatorUserId。所有提交校验仍在，待批重复判断必须移到学期锁后，不能沿用目前锁前查询。
- 批准/驳回/撤回使用具体的生命周期操作服务，统一学期→申请锁序与锁后刷新；模块应隐藏状态守卫、截止判定、最终代课人验证、历史与生效写入。不要给三条路径各复制一套锁/状态机，也不要为少量动作造策略工厂。
- 处理入口需要 requestId→semesterId 的轻量定位来选锁；这个定位只是导航，不是锁后权威状态。取得学期锁/申请锁后须刷新，再检查权限和状态，不能沿用 JPA 一级缓存中的旧 SUBMITTED。
- 批准复用已有 cover 效果实现，expectedTeacher 保留提交原值，finalTeacher 记录实际审批选择；按当前课表核对请求对应实例、previousTeacher/槽位及基线存在，再验撞课、教师有效性和真正开课截止。旧 EFFECTIVE 行只在批准全部验证通过后退出；失败不产生新生效行和成功历史。
- 管理端工作台仍走原 AdminSubstitutionService，不为直接确认套审批。新待批列表与老师本人列表可复用 DTO，明确审批意见/结果字段，不拿内部 exception 文案冒充驳回理由。

## 作废必须真落库

推荐惰性作废：请求已过开课截止，锁内保存 EXPIRED 与历史，事务正常提交；批准对外返回冲突。不能在同一个普通 @Transactional 方法保存后直接抛 ApiException，导致 EXPIRED 回滚。

可用一个具体操作入口在 TransactionTemplate 内返回成功/已作废结果，模板返回后再转 HTTP 错误；也可用独立具体事务 bean 正常返回结果。二者选最小方案即可，不造通用 Result/事务框架。不要对所有 ApiException 设置 noRollbackFor，那会同时保留本应回滚的失败副作用。

列表/详情若触发作废，也必须使用可写事务并按同一锁序重读；原 readOnly=true 方法不能声称写入已提交。只处理调用者有权访问的记录，不借“作废”放大读取权限。验证过期批准返回失败后再另发 GET，确实读到 EXPIRED、一次历史且无新 substitution。

## 本人、历史兼容与通知边界

- 新请求的撤回/“我的”首先认实际 operatorUserId；teacherId 只在明确非空时比较，禁止 Objects.equals(null,null) 变成本人。
- 旧行无 operatorUserId 不猜造操作者。仅在既有兼容语义下用非空教师关联认本人；教师和管理查询权限分别明确。保留班主任历史分支源码须受退休的本班记录权限约束，不能只因碰巧仍是班主任字段就放行。
- 批准/驳回后通知收件人为实际申请账号；05 先提供真实状态转换与可供06接入的数据，不自行恢复旧抄送。最终通知种类与去重在06收口；提交时绝不发“已接课”通知。
- TDD 采用已确认的 HTTP→落库/历史/效果边界；必要并发测试强制旧快照交错，再补公开 HTTP 不变量。不能靠不稳定线程时序声称“后到者必失败”。

用户可见流程及截止、撤回、改人批准、驳回、工作台直接生效均须有定向证据。旧即时生效测试需通过真实审批再验证后续行为，不能把原有日期/再次调整覆盖删掉以求绿。

05首个切片复读补充：`verifyCoverOrReject`原来传`audits.failure(..., true)`，其REQUIRES_NEW失败审计仍会调用`notifications.substitutionVerifyAlert`。05本申请服务/新审批入口应传alert=false，保留失败审计但不在失败分支偷发旧告警；工作台其它旧失败触发器留给06统一收口。撞课拒绝回归可断无新增通知记录，不只检查成功路径已删除notifyEffective。

2026-11-12早期QA发现的清扫竞态：expireOverdue的pending查询在学期锁前，查询命中的申请在等锁期间可能已被批准/撤回/驳回；refresh后必须重新确认仍SUBMITTED且已过截止，再保存EXPIRED。不能只重验截止，否则会把已EFFECTIVE的申请改成EXPIRED而效果仍生效。生产writer已在8f457ac修正；主线程独立质量工作树补可控Clock+latch交错回归（cab9002，受控破坏时红、原字节恢复后绿），与并发writer原五例分开计数。没有生产私有方法调用次数断言，不靠sleep时序。
