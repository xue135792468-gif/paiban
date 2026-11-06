import { describe, expect, it } from 'vitest'
import { cardsFromProposal } from './proposalToCards'
import { describeSentence, type RuleNames } from './compiler'
import type { ConstraintProposal, ProposalEntry } from '@/components/constraints/useConstraintAi'

const names: RuleNames = {
  teacher: (id) => ({ 7: '王老师', 8: '李老师' } as Record<number, string>)[id] ?? `教师${id}`,
  course: (id) => `课程${id}`,
  klass: (id) => ({ 1: '九（1）班', 2: '九（2）班', 3: '九（3）班' } as Record<number, string>)[id]
    ?? `班级${id}`,
}

function proposal(overrides: Partial<ConstraintProposal>): ConstraintProposal {
  return {
    proposalId: 'p1',
    sourceText: '王老师周三下午不排课',
    type: 'TEACHER_UNAVAILABLE',
    typeName: '教师禁排时段',
    strength: 'HARD',
    effectiveHard: true,
    effectiveWeight: null,
    status: 'READY',
    explanation: '',
    reason: '',
    params: {},
    entries: [],
    candidates: [],
    ...overrides,
  }
}

function slot(extra: Record<string, unknown>, day: number, period: number): ProposalEntry {
  return { params: { ...extra, dayOfWeek: day, periodNo: period } }
}

describe('cardsFromProposal: suggestions become unsaved rule cards', () => {
  it('turns a teacher slot set into one new card and one sentence', () => {
    const result = cardsFromProposal([
      proposal({ entries: [5, 6, 7, 8].map((period) => slot({ teacherId: 7 }, 3, period)) }),
    ], { allClassIds: [] })

    expect(result.rejected).toEqual([])
    expect(result.decisions).toHaveLength(1)
    expect(result.cards).toHaveLength(1)

    const card = result.cards[0]
    expect(card.cardId).toBeTruthy()
    expect(card.key).toBe(`card:${card.cardId}`)
    expect(card.sentences).toHaveLength(1)

    const sentence = card.sentences[0]
    expect(sentence.type).toBe('TEACHER_UNAVAILABLE')
    expect(sentence.teacherId).toBe(7)
    expect(sentence.days).toEqual([3])
    expect(sentence.periodNos).toEqual([5, 6, 7, 8])
    expect(sentence.entryIds).toEqual({})
    expect(sentence.groupKey).toBe(`${card.cardId}:0`)
    expect(sentence.key).toBe(`group:${card.cardId}:0`)
    expect(describeSentence(sentence, names)).toBe('王老师 周三第 5、6、7、8 节 不上课')
  })

  it('splits two teachers into two cards', () => {
    const result = cardsFromProposal([
      proposal({
        entries: [
          ...[5, 6, 7, 8].map((period) => slot({ teacherId: 7 }, 3, period)),
          ...[5, 6, 7, 8].map((period) => slot({ teacherId: 8 }, 4, period)),
        ],
      }),
    ], { allClassIds: [] })

    expect(result.cards).toHaveLength(2)
    expect(result.cards.map((card) => card.sentences[0].teacherId)).toEqual([7, 8])
    expect(result.decisions).toHaveLength(1)
  })

  it('reads full class coverage as one school-wide rule', () => {
    const result = cardsFromProposal([
      proposal({
        type: 'CLASS_UNAVAILABLE',
        typeName: '班级禁排时段',
        entries: [slot({ classId: 1 }, 1, 1), slot({ classId: 2 }, 1, 1)],
      }),
    ], { allClassIds: [1, 2] })

    expect(result.rejected).toEqual([])
    expect(result.cards).toHaveLength(1)
    const sentence = result.cards[0].sentences[0]
    expect(sentence.classId).toBeNull()
    expect(sentence.exceptionClassIds).toEqual([])
    expect(describeSentence(sentence, names)).toBe('全校 周一第 1 节 不上课')
  })

  it('reads partial class coverage as "every class except"', () => {
    const result = cardsFromProposal([
      proposal({
        type: 'CLASS_UNAVAILABLE',
        typeName: '班级禁排时段',
        entries: [slot({ classId: 1 }, 1, 1), slot({ classId: 2 }, 1, 1)],
      }),
    ], { allClassIds: [1, 2, 3] })

    expect(result.rejected).toEqual([])
    expect(result.cards).toHaveLength(1)
    const sentence = result.cards[0].sentences[0]
    expect(sentence.classId).toBeNull()
    expect(sentence.exceptionClassIds).toEqual([3])
    expect(describeSentence(sentence, names)).toBe('全校 周一第 1 节 不上课，除了 九（3）班')
  })

  it('keeps the global teacher scope when the suggestion names no teacher', () => {
    const result = cardsFromProposal([
      proposal({
        type: 'TEACHER_MAX_PER_DAY',
        typeName: '教师每天最多节数',
        entries: [{ params: { maxPerDay: 4 } }],
        effectiveWeight: 60,
      }),
    ], { allClassIds: [] })

    expect(result.rejected).toEqual([])
    expect(result.cards).toHaveLength(1)
    const sentence = result.cards[0].sentences[0]
    expect(sentence.teacherId).toBeNull()
    expect(sentence.amount).toBe(4)
    expect(describeSentence(sentence, names)).toBe('所有教师 每天最多 4 节')
  })

  it('converts a switch suggestion into one card', () => {
    const result = cardsFromProposal([
      proposal({ type: 'CLASS_GAP_MINIMIZED', typeName: '班级不空堂', entries: [] }),
    ], { allClassIds: [] })

    expect(result.rejected).toEqual([])
    expect(result.cards).toHaveLength(1)
    expect(result.cards[0].sentences[0].type).toBe('CLASS_GAP_MINIMIZED')
    expect(describeSentence(result.cards[0].sentences[0], names)).toBe('班级 不空堂')
  })

  it('rejects a type the rule page does not manage', () => {
    const result = cardsFromProposal([
      proposal({ type: 'TEACHER_CONFLICT', typeName: '教师冲突', entries: [{ params: {} }] }),
    ], { allClassIds: [] })

    expect(result.cards).toEqual([])
    expect(result.decisions).toEqual([])
    expect(result.rejected).toHaveLength(1)
    expect(result.rejected[0].typeName).toBe('教师冲突')
    expect(result.rejected[0].reason).toContain('暂不支持在规则页添加')
  })

  it('rejects a non-switch suggestion without any entries', () => {
    const result = cardsFromProposal([
      proposal({ type: 'COURSE_UNAVAILABLE', typeName: '课程禁排时段', entries: [] }),
    ], { allClassIds: [] })

    expect(result.cards).toEqual([])
    expect(result.rejected).toHaveLength(1)
    expect(result.rejected[0].reason).toContain('没有可转换的条件')
  })

  it('rejects an unresolved suggestion with the server reason', () => {
    const result = cardsFromProposal([
      proposal({ status: 'NEEDS_INPUT', reason: '同名教师需要选择' }),
    ], { allClassIds: [] })

    expect(result.cards).toEqual([])
    expect(result.rejected[0].reason).toBe('同名教师需要选择')
  })

  it('records the confirmed rows, type and action in the decision', () => {
    const entries = [5, 6, 7, 8].map((period) => slot({ teacherId: 7 }, 3, period))
    const result = cardsFromProposal([
      proposal({ explanation: '周三下午全部禁排', entries }),
    ], { allClassIds: [] })

    expect(result.decisions).toEqual([{
      proposalId: 'p1',
      action: 'ADDED',
      type: 'TEACHER_UNAVAILABLE',
      reason: '周三下午全部禁排',
      entries: entries.map((entry) => entry.params),
    }])
  })
})
