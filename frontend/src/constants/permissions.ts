/**
 * 管理调代工作台的权限组合（规格第 1 节）：
 * 全校课表查看 + 提交调代 + 审批调代；仅查看权限不可写。
 * 与后端 AdminSubstitutionService.MANAGER_PERMISSIONS 保持同一组合。
 */
export const WORKBENCH_MANAGER_PERMISSIONS = [
  'timetable:view',
  'substitution:submit',
  'substitution:approve',
]
