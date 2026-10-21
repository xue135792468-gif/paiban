/** Target shapes the import hub offers. Adding a target here is the only UI change. */
export interface ImportTargetCard {
  code: string
  title: string
  available: boolean
  /** What the target can read. */
  handles: string
  /** What the import produces. */
  output: string
  /** Boundaries the user should know before uploading. */
  limits: string
  excelHint: string
  textPlaceholder: string
}

export const IMPORT_TARGETS: ImportTargetCard[] = [
  {
    code: 'teacher',
    title: '教师',
    available: true,
    handles: '教师名单、通讯录、任课表；姓名、工号、电话、学科可以乱序',
    output: '教师档案：姓名、工号、电话、邮箱、学科，入库后可在教师页继续编辑',
    limits: '图片导入需要视觉模型；同名教师不会自动合并，需要人工核对',
    excelHint: '支持格式混乱的表格，AI 会自动识别姓名、学科、电话等列。',
    textPlaceholder: '例如：张老师教语文，电话 13800000001；李老师教数学，工号 T002……',
  },
  {
    code: 'timetable',
    title: '课表',
    available: true,
    handles: '年级课表、班级课表（班级 × 星期 × 节次），一行一节课',
    output: '一份课表草稿，可在草稿编辑器里调整后再发布',
    limits: '只生成草稿，不直接改动已发布课表；班级、课程、教师需与系统数据匹配',
    excelHint: '支持年级课表、班级课表，AI 会按班级、星期、节次读出每节课。',
    textPlaceholder: '例如：高一（1）班 周一第 1 节 语文 张老师；周一第 2 节 数学 李老师……',
  },
  {
    code: 'admin-class',
    title: '行政班',
    available: false,
    handles: '年级班级名单、班主任安排表',
    output: '行政班档案：班级名称、所属年级、班主任、学生人数',
    limits: '即将支持，当前请用「行政班」页面录入',
    excelHint: '',
    textPlaceholder: '',
  },
  {
    code: 'course',
    title: '课程',
    available: false,
    handles: '课程清单、科目设置表',
    output: '课程档案：课程名称、科目、是否连堂、所需教室类型',
    limits: '即将支持，当前请用「课程」页面录入',
    excelHint: '',
    textPlaceholder: '',
  },
  {
    code: 'room',
    title: '教室',
    available: false,
    handles: '教室清单、场地安排表',
    output: '教室档案：名称、容量、类型、楼栋',
    limits: '即将支持，当前请用「教室」页面录入',
    excelHint: '',
    textPlaceholder: '',
  },
  {
    code: 'assignment',
    title: '授课任务',
    available: false,
    handles: '任课安排表（班级 × 课程 × 教师）',
    output: '授课任务：班级、课程、教师、周课时',
    limits: '即将支持，当前请用「授课任务」页面录入',
    excelHint: '',
    textPlaceholder: '',
  },
]

export function targetCard(code: string): ImportTargetCard | undefined {
  return IMPORT_TARGETS.find((target) => target.code === code)
}

export function targetTitle(code: string): string {
  return targetCard(code)?.title ?? code
}

/** Query parameters may name a target; unknown or unavailable codes fall back to teacher. */
export function preselectedTarget(query: unknown): string {
  const requested = typeof query === 'string' ? query : ''
  const match = IMPORT_TARGETS.find((target) => target.code === requested && target.available)
  return match?.code ?? 'teacher'
}
