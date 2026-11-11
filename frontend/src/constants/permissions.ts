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

/**
 * 「草稿与排课」这类管理入口的唯一判据：能跑排课或能编辑课表。
 * 老师新增的 `timetable:view` 只是只读扩权，两项都不含它。
 * 路由 `permissionAny`、工作台页签、导航落地页与课表页的排课入口共用这一份，
 * 免得几处各写一遍 `scheduling:run || timetable:edit` 后悄悄漂移。
 */
export const DRAFT_MANAGER_PERMISSIONS = ['scheduling:run', 'timetable:edit'] as const

export function canManageDrafts(has: (permission: string) => boolean): boolean {
  return DRAFT_MANAGER_PERMISSIONS.some((permission) => has(permission))
}
