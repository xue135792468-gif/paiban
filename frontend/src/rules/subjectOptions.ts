import type { OptionItem } from '@/components/constraints/types'

/** One option of the 「谁 / 什么课」 dropdown. */
export interface SubjectOption {
  value: string
  label: string
  testId?: string
}

interface GroupOption {
  id: number
  name: string
  /** Optional so existing callers/tests can pass bare `{id, name}` groups. */
  memberIds?: number[]
}

/** 「教导主任、语文组」: the live group names one teacher / course belongs to. */
function groupSuffix(groups: GroupOption[] | undefined, objectId: number): string {
  const names = (groups ?? [])
    .filter((group) => group.memberIds?.includes(objectId))
    .map((group) => group.name)
  return names.length === 0 ? '' : `（${names.join('、')}）`
}

/** 人 / 班级: school, teachers, teacher groups, classes, teachers, 新建教师组. */
export function teacherSubjectOptions(input: {
  teacherGroups?: GroupOption[]
  classes: OptionItem[]
  teachers: OptionItem[]
  canCreate: boolean
}): SubjectOption[] {
  return [
    { value: 'school', label: '全校' },
    { value: 'all-teachers', label: '所有教师' },
    ...(input.teacherGroups ?? []).map((group) => ({
      value: `teacher-group:${group.id}`,
      label: `教师组 · ${group.name}`,
    })),
    ...input.classes.map((item) => ({
      value: `class:${item.id}`,
      label: `班级 · ${item.name}`,
    })),
    ...input.teachers.map((item) => ({
      value: `teacher:${item.id}`,
      label: `教师 · ${item.name}${groupSuffix(input.teacherGroups, item.id)}`,
    })),
    ...(input.canCreate
      ? [{ value: '__new_teacher_group__', label: '＋ 新建教师组', testId: 'rule-subject-new-group' }]
      : []),
  ]
}

/** 课程: all courses, course groups, courses, 新建课程组. */
export function courseSubjectOptions(input: {
  courseGroups?: GroupOption[]
  courses: OptionItem[]
  canCreate: boolean
}): SubjectOption[] {
  return [
    { value: 'all-courses', label: '全部课程' },
    ...(input.courseGroups ?? []).map((group) => ({
      value: `course-group:${group.id}`,
      label: `课程组 · ${group.name}`,
    })),
    ...input.courses.map((item) => ({
      value: `course:${item.id}`,
      label: `课程 · ${item.name}${groupSuffix(input.courseGroups, item.id)}`,
    })),
    ...(input.canCreate
      ? [{ value: '__new_course_group__', label: '＋ 新建课程组', testId: 'rule-subject-new-course-group' }]
      : []),
  ]
}
