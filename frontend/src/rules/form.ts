import {
  CLASS_UNAVAILABLE,
  RULE_SPECS,
  normalizePeriods,
  type RuleInput,
  type RuleSentence,
  type RuleStrength,
  type RuleType,
} from './compiler'

/** The mutable sentence shape the rule form edits (no storage identity beyond `key`). */
export interface FormSentence {
  key: string | null
  type: RuleType
  classId: number | null
  teacherId: number | null
  courseId: number | null
  dayOfWeek: number | null
  periodNos: number[]
  amount: number | null
  strength: RuleStrength
  exceptionClassIds: number[]
}

export function formSentenceFrom(sentence: RuleSentence): FormSentence {
  return {
    key: sentence.key,
    type: sentence.type,
    classId: sentence.classId,
    teacherId: sentence.teacherId,
    courseId: sentence.courseId,
    dayOfWeek: sentence.dayOfWeek,
    periodNos: [...sentence.periodNos],
    amount: sentence.amount,
    strength: sentence.strength,
    exceptionClassIds: [...sentence.exceptionClassIds],
  }
}

export function blankFormSentence(): FormSentence {
  return {
    key: null,
    type: CLASS_UNAVAILABLE,
    classId: null,
    teacherId: null,
    courseId: null,
    dayOfWeek: null,
    periodNos: [],
    amount: null,
    strength: 'HARD',
    exceptionClassIds: [],
  }
}

/** Turns one edited sentence back into the pure compiler input. */
export function formSentenceInput(sentence: FormSentence): RuleInput {
  const rule = RULE_SPECS[sentence.type]
  return {
    type: sentence.type,
    classId: sentence.classId,
    teacherId: sentence.teacherId,
    courseId: sentence.courseId,
    dayOfWeek: sentence.dayOfWeek,
    periodNos: normalizePeriods(sentence.periodNos),
    amount: sentence.amount,
    strength: rule.hardOnly ? 'HARD' : sentence.strength,
    exceptionClassIds: [...sentence.exceptionClassIds],
  }
}
