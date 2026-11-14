# 04 切片接口契约（已实现并复核）

2026-11-11。A 后端只读预演/扫描完整性，B 前端选择与确认界面，C 后端确认停用/整合验收。A/B写集独立；整票全部合入并真实浏览器验收、双轴复核后才能取下一张票。

既有预演字段保持，追加：

```ts
interface DeactivationCandidate {
  accountId: number
  accountName: string
  displayName: string
  teacherId: number
  teacherName: string
  dingtalkUserId: string
}
// SyncPreview 新字段
scanComplete: boolean
deactivationWarning: string | null
deactivations: DeactivationCandidate[]
```

仅完整扫描可返回候选。用户/部门导入排除不影响原始UID可见集合。失败响应 scanComplete=false、候选为空，明确失败/警告；字段缺失不能被前端解释成完整扫描。

既有 ConfirmRequest 三字段保持，追加可选数组 `deactivationSelections`，每项 `{ accountId, teacherId, dingtalkUserId }`。null/未传/空数组均不执行停用。其余三个旧字段不能隐式开启缺失停用。

界面仅由当前预演候选生成选择，默认勾选，可逐个排除；新预演替换旧选择。必须完整扫描且有候选才显示停用选择。确认存在停用时二次提示“不删档案和课表，可在账号页重新启用”。仅停用无导入也能确认，按钮分别写清导入人数和停用账号数。

C 在事务外重扫，完整性验证后，统一账号锚点锁内重新读本地关系和候选。逐项核对候选及所传期望teacherId/UID；漂移拒绝要求重新预演，不扩大选择。选择中任一目标不合法时整批不半写，self/lastsuper保护和逐账号SYNC_DISABLE审计复用统一实现。

OrgSyncResult 增加独立 `accountDisabled` 数字；原 `deactivated` 仍是远端inactive教师指标。确认消息如实分别计数，不把两者合成一个“停用人数”。

阶段性代码不代表票04完成。A/B可先提供定向测试与浏览器用例准备，最终C整合后执行真实API+Playwright与截图，之后双轴复核。
