import type { HelpSlug } from '@/help/links'

/**
 * One step of the first-run wizard. `helpAnchor` points at the matching section
 * of the term-start flow document, so the wizard and the help centre stay in sync.
 */
export interface WizardStep {
  key: string
  title: string
  description: string
  route: string
  helpAnchor: string
}

/** Every step explains itself through the "new term" scenario document. */
export const WIZARD_HELP_SLUG: HelpSlug = 'flow-term-start'

export const WIZARD_STEPS: WizardStep[] = [
  {
    key: 'school',
    title: '学校信息',
    description: '填写学校名称并上传校徽，课表导出时会使用这些信息。',
    route: '/admin/settings',
    helpAnchor: 'school',
  },
  {
    key: 'periods',
    title: '学期与作息表',
    description: '创建本学期并配置每天几节课、上午/下午/晚自习的时间。',
    route: '/admin/semesters',
    helpAnchor: 'periods',
  },
  {
    key: 'grades',
    title: '年级与行政班',
    description: '录入年级和行政班，可下载模板后 Excel 批量导入。',
    route: '/admin/grades',
    helpAnchor: 'grades',
  },
  {
    key: 'teachers',
    title: '教师',
    description: '录入教师名单，可 Excel 批量导入；后续用于授课任务与登录账号。',
    route: '/admin/teachers',
    helpAnchor: 'teachers',
  },
  {
    key: 'rooms',
    title: '教室',
    description: '录入普通教室、实验室、操场等场地及容量。',
    route: '/admin/rooms',
    helpAnchor: 'rooms',
  },
  {
    key: 'courses',
    title: '课程',
    description: '维护课程目录与元数据（是否允许连堂、是否必须室外）。',
    route: '/admin/courses',
    helpAnchor: 'courses',
  },
  {
    key: 'assignments',
    title: '授课任务',
    description: '录入「行政班 + 课程 + 每周节数 + 教师」，这是排课的输入。',
    route: '/admin/assignments',
    helpAnchor: 'assignments',
  },
  {
    key: 'constraints',
    title: '约束规则',
    description: '勾选启用的排课约束并调整权重，默认已启用教师/行政班/教室不冲突。',
    route: '/admin/constraints',
    helpAnchor: 'constraints',
  },
]

/** "第 X/N 步：步骤名称" — N comes from the real step list, never a hard-coded 6. */
export function wizardProgressLabel(index: number, steps: WizardStep[] = WIZARD_STEPS): string {
  const safeIndex = Math.min(Math.max(index, 0), steps.length - 1)
  return `第 ${safeIndex + 1}/${steps.length} 步：${steps[safeIndex].title}`
}
