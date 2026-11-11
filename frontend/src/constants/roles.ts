/**
 * 钉钉多用户（票 01）之后只剩三种预置身份。前端只按权限判断能力，
 * 这份清单只用于「改身份」选项与身份徽标文案，不参与任何权限判断。
 *
 * `head_teacher`（班主任）已下线：迁移会把持有它的账号改成老师，
 * 因此这里不再给出它的产品名称。
 */
export interface PresetRole {
  code: string
  label: string
}

export const PRESET_ROLES: PresetRole[] = [
  { code: 'super_admin', label: '超级管理员' },
  { code: 'admin', label: '教务管理员' },
  { code: 'teacher', label: '老师' },
]

const LABELS = new Map(PRESET_ROLES.map((role) => [role.code, role.label]))

/** Product name of a role code; unknown/legacy codes fall back to the raw code. */
export function presetRoleLabel(code: string): string {
  return LABELS.get(code) ?? code
}

/**
 * The highest preset identity an account holds, in `PRESET_ROLES` order
 * (超级管理员 > 教务管理员 > 老师). The backend returns role codes alphabetically,
 * so `roles[0]` is NOT the strongest identity: an account holding
 * `['admin', 'super_admin', 'teacher']` must read as 超级管理员, not 教务管理员.
 */
export function primaryRoleCode(roles: string[]): string | null {
  const held = new Set(roles)
  return PRESET_ROLES.find((role) => held.has(role.code))?.code ?? null
}

/**
 * One badge per account: the highest preset identity it holds. Roles outside
 * the preset list (legacy codes) are ignored so they never read as a product role.
 */
export function primaryRoleLabel(roles: string[]): string {
  const code = primaryRoleCode(roles)
  return code ? presetRoleLabel(code) : ''
}

export function isPresetRoleCode(code: string): boolean {
  return LABELS.has(code)
}
