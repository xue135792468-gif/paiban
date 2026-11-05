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
    route: '/settings/general',
    helpAnchor: 'school',
  },
  {
    key: 'periods',
    title: '学期与作息表',
    description: '创建本学期并配置每天几节课。',
    route: '/data/semesters',
    helpAnchor: 'periods',
  },
  {
    key: 'grades',
    title: '年级与行政班',
    description: '录入年级和行政班，可下载模板后 Excel 批量导入。',
    route: '/data/grades',
    helpAnchor: 'grades',
  },
  {
    key: 'teachers',
    title: '教师',
    description: '录入教师名单，可 Excel 批量导入；后续用于授课任务与登录账号。',
    route: '/data/teachers',
    helpAnchor: 'teachers',
  },
  {
    key: 'courses',
    title: '课程',
    description: '维护课程目录（是否允许连堂）与课表颜色。课程全校共用，新学期通常不用重建。',
    route: '/data/courses',
    helpAnchor: 'courses',
  },
  {
    key: 'assignments',
    title: '授课任务',
    description: '录入「行政班 + 课程 + 每周节数 + 教师」，这是排课的输入。',
    route: '/data/assignments',
    helpAnchor: 'assignments',
  },
  {
    key: 'constraints',
    title: '排课规则',
    description: '用「对谁 / 哪些课 / 什么时候 / 要求」拼规则；不写规则也能直接排课。',
    route: '/rules',
    helpAnchor: 'constraints',
  },
]

/** "第 X/N 步：步骤名称" — N comes from the real step list, never a hard-coded 6. */
export function wizardProgressLabel(index: number, steps: WizardStep[] = WIZARD_STEPS): string {
  const safeIndex = Math.min(Math.max(index, 0), steps.length - 1)
  return `第 ${safeIndex + 1}/${steps.length} 步：${steps[safeIndex].title}`
}
