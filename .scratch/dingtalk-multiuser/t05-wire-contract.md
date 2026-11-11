# 05 实施接口与验收契约（开工准备）

日期：2026-11-11。03已放行，05从集成e091d0f开始施工；本文件是契约，不是验收证明。正式需求见 spec.md、issues/05-substitution-approval.md，事务细节见 t05-transaction-plan.md，调用方保全见 t05-caller-regression-map.md。

## 已决接口与状态

- 老师自助仅 COVER，提交只保存 SUBMITTED，不立即生效；工作台仍直接生效。旧 Create DTO shape 保留，SWAP 仍明确拒绝。
- `POST /api/substitution-requests/{id}/approve` 接收可选 `{substituteTeacherId, comment}`；未给人选用期望人。期望人不可覆盖，最终人记录实际批准选择。
- `POST .../{id}/reject` 接收必填 `{reason}`；`POST .../{id}/withdraw` 无体，仅申请人且仅 SUBMITTED 可撤。
- 自助审批主链状态为 SUBMITTED/EFFECTIVE/REJECTED/WITHDRAWN/EXPIRED；CANCELLED 保留既有生效安排被系统撤销时的展示，不等同申请人撤回。这里的legacy指既有代码与发布撤回语义，不限于上线前旧行；例如管理员撤回已发布基线后，新近已生效安排仍按既有流程转CANCELLED，不能伪装成尚未审批的EXPIRED或删除原回归断言。
- 管理待批复用 GET 的显式 `status=SUBMITTED`；`mine=true` 认实际 operatorUserId。不得擅改共享查询默认值。
- DTO 追加 expectedSubstituteTeacherId/Name、approverUserId、approvedAt、approvalComment、operatorUserId。既有 substituteTeacherId/Name 表示已生效实际人或待批期望人，保留旧构造器兼容。

## 数据、事务和安全边界

- 新请求保存真实 operatorUserId。旧无 operator 行仅在非空教师关系成立时认本人，不得因两个 null 相等放行；班主任历史读取仍需退休角色对应权限。
- 学期→申请统一锁序并 refresh；同实例待批唯一检查在学期锁内。并发晚到处理返回409，无部分生效。
- 批准按当前发布基线、当前实例原教师/槽位、最终教师状态、撞课和真实开课截止重验。全部通过才退出旧 EFFECTIVE 并落新效果。
- 惰性作废在具体可写事务中保存 EXPIRED 和历史，提交后再返回409；列表/详情只处理有权访问的记录。禁止对所有 ApiException 免回滚。
- submit/approve/reject/withdraw/expire 各一次正常同事务 AuditLog 和状态历史；记录真实 actor、request、前后状态、期望/实际人。现有 REQUIRES_NEW 失败审计不承担成功动作；不改全局 recordHistory 暗加重复审计。
- 05 新提交/审批路径不调用既有事务内同步网络通知。06接入提交后发送；工作台旧通知留给06收口。整个功能须06及08完成才交付。
- 新迁移候选 V63、新ADR候选0037；开始前重查所有分支/worktree占用，不改历史迁移或历史ADR。旧 import-repairs 存在不同内容的未跟踪V24，不属于本次合并写集，不可声称所有工作树迁移无冲突。

## 前端与验收

- 新 PendingPanel 仅 approve 权限显示；教师表单去SWAP，原因提示“想调课请写明，由教务处理”；我的申请显示状态、驳回理由、仅待批可撤。
- HomeView 调代卡片按能力区分教师申请等待审批和管理工作台直接调整，不改导航权限架构。
- 后端覆盖提交不生效、各拒绝边界、改人批准、驳回、撤回、真实落库作废、旧快照并发及HTTP不变量、工作台保持直接生效。
- 旧自助即时生效测试通过真实 approve 后继续检验原日期、多周、再次调整和历史格式，不删除原业务断言求绿。
- 前端组件定向测试和真实 Playwright 覆盖教师提交/撤回、管理待批改人批准/驳回；08另做手机到电脑整链。
- 按TDD逐条红绿，只跑定向测试；05施工完成后固定基点双轴code-review并整改，再进入06。
