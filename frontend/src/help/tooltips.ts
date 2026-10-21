/**
 * Inline help copy for fields that are easy to misread.
 *
 * Every tooltip is a short, plain-language sentence aimed at the school
 * scheduler (教务老师), not at developers. Keys are namespaced by feature so a
 * field can be looked up from any view without importing that view.
 */
export const TOOLTIP_KEYS = [
  'constraint.weight',
  'constraint.hard',
  'constraint.param',
  'course.consecutive',
  'assignment.weeklyLessons',
  'substitution.approvalMode',
  'substitution.effectiveDate',
  'timetable.view',
  'timetable.substitutionMark',
  'settings.schoolType',
  'settings.aiProvider',
  'settings.platformSwitch',
] as const

export type TooltipKey = (typeof TOOLTIP_KEYS)[number]

export const TOOLTIPS: Record<TooltipKey, string> = {
  'constraint.weight':
    '0-100 表示优先级，越高越优先满足；只有软约束可以调权重，硬约束始终优先。',
  'constraint.hard':
    '硬约束必须满足，排不出来会提示冲突；软约束尽量满足，冲突时按权重取舍。初中模式下两者显示为「必须」「尽量」。',
  'constraint.param':
    '该约束生效所需的参数，带 * 为必填；未填写的必填参数在保存时会提示。',
  'course.consecutive':
    '允许该课程连排两节及以上（如作文、实验）；关闭后每次只排单节。',
  'assignment.weeklyLessons':
    '该行政班这门课每周要排的课时数，班级全部课程合计不宜超过一周可排节次。',
  'substitution.approvalMode':
    '审批模式：申请经教务批准后才生效；直接生效：提交即生效，适合教师自主调代。',
  'substitution.effectiveDate':
    '调代实际生效的日期，课表上这一天的这节课会显示代课教师。',
  'timetable.view':
    '最新：包含已生效调代与最近发布的结果；原始：学期初始发布的基线课表，不含调代。',
  'timetable.substitutionMark':
    '「代」表示该课节已由其他教师代课，悬停可查看原教师与调代原因。',
  'settings.schoolType':
    '初中模式会隐藏权重、模板、多草稿对比等高级功能；切换后页面会自动刷新。',
  'settings.aiProvider':
    '配置 AI 供应商、模型与任务分配；未配置时 AI 功能保持隐藏，不影响排课。',
  'settings.platformSwitch':
    '同一时间只启用一个 IM 平台；切换会停用原平台并同步新平台组织，业务数据完整保留。',
}
