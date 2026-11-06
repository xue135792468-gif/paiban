import {
  RULE_SPECS,
  normalizeDays,
  normalizePeriods,
  type RuleInput,
  type RuleSentence,
  type RuleStrength,
  type RuleType,
  type SubjectKind,
} from './compiler'

/** The mutable sentence shape the rule form edits (no storage identity beyond `key`). */
export interface FormSentence {
  key: string | null
  /** Null until the user picks a requirement. */
  type: RuleType | null
  /**
   * What the first slot ("谁 / 什么课") holds. Form-only: the saved sentence keeps
   * the concrete id (or its absence = 全校 / 所有教师 / 全部课程).
   */
  subjectKind: SubjectKind | null
  classId: number | null
  teacherId: number | null
  courseId: number | null
  days: number[]
  periodNos: number[]
  amount: number | null
  strength: RuleStrength
  exceptionClassIds: number[]
  /** Ticket 04: teacher group reference; concrete ids are null while a group is used. */
  teacherGroupId: number | null
  courseGroupId: number | null
  timeGroupId: number | null
}

/** Infers the subject a stored sentence was written with. */
function subjectKindOf(
  type: RuleType | null,
  classId: number | null,
  teacherId: number | null,
  courseId: number | null,
  teacherGroupId: number | null = null,
  courseGroupId: number | null = null,
): SubjectKind | null {
  if (courseGroupId !== null) {
    return 'COURSE'
  }
  if (teacherGroupId !== null) {
    return 'TEACHER_GROUP'
  }
  if (courseId !== null) {
    return 'COURSE'
  }
  if (teacherId !== null) {
    return 'TEACHER'
  }
  if (classId !== null) {
    return 'CLASS'
  }
  if (!type) {
    return null
  }
  const target = RULE_SPECS[type].target
  if (target === 'COURSE') {
    return 'ALL_COURSES'
  }
  if (target === 'TEACHER') {
    return 'ALL_TEACHERS'
  }
  if (target === 'CLASS') {
    return 'SCHOOL'
  }
  return 'SCHOOL'
}

export function formSentenceFrom(sentence: RuleSentence): FormSentence {
  return {
    key: sentence.key,
    type: sentence.type,
    subjectKind: subjectKindOf(
      sentence.type,
      sentence.classId,
      sentence.teacherId,
      sentence.courseId,
      sentence.teacherGroupId,
      sentence.courseGroupId,
    ),
    classId: sentence.classId,
    teacherId: sentence.teacherId,
    courseId: sentence.courseId,
    days: [...sentence.days],
    periodNos: [...sentence.periodNos],
    amount: sentence.amount,
    strength: sentence.strength,
    exceptionClassIds: [...sentence.exceptionClassIds],
    teacherGroupId: sentence.teacherGroupId,
    courseGroupId: sentence.courseGroupId,
    timeGroupId: sentence.timeGroupId,
  }
}

export function blankFormSentence(): FormSentence {
  return {
    key: null,
    type: null,
    subjectKind: null,
    classId: null,
    teacherId: null,
    courseId: null,
    days: [],
    periodNos: [],
    amount: null,
    strength: 'HARD',
    exceptionClassIds: [],
    teacherGroupId: null,
    courseGroupId: null,
    timeGroupId: null,
  }
}

/** Turns one edited sentence back into the pure compiler input, or null while incomplete. */
export function formSentenceInput(sentence: FormSentence): RuleInput | null {
  if (!sentence.type) {
    return null
  }
  const rule = RULE_SPECS[sentence.type]
  return {
    type: sentence.type,
    classId: sentence.classId,
    teacherId: sentence.teacherId,
    courseId: sentence.courseId,
    teacherGroupId: sentence.teacherGroupId,
    courseGroupId: sentence.courseGroupId,
    timeGroupId: sentence.timeGroupId,
    days: normalizeDays(sentence.days),
    periodNos: normalizePeriods(sentence.periodNos),
    amount: sentence.amount,
    strength: rule.hardOnly ? 'HARD' : sentence.strength,
    exceptionClassIds: [...sentence.exceptionClassIds],
  }
}
