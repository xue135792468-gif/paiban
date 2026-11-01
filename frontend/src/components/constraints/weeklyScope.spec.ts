import { describe, expect, it } from 'vitest'
import { useConstraintDraft } from './useConstraintDraft'
import { plannedEntries } from './entryModel'
import { duplicateMessages } from './entryChecks'
import type { ConstraintTypeDef } from './types'

const type: ConstraintTypeDef = {
  code: 'CLASS_UNAVAILABLE', name: '每周不上课时段', category: 'PEDAGOGY', categoryLabel: '教学规律',
  description: '', defaultHard: true, defaultWeight: 100, hardOnly: true, slotGrouped: true,
  params: [
    { name: 'classId', label: '班级', type: 'ID', required: false },
    { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
    { name: 'periodNo', label: '节次', type: 'PERIOD', required: true },
  ],
}
function load(entries: Array<{ id: number; params: Record<string, unknown>; groupKey: string }>) {
  const draft = useConstraintDraft()
  draft.hydrate([type], [{ type: type.code, enabled: true, hard: true, weight: 100, params: {}, entries }], { isJunior: false })
  return draft
}
describe('weekly no-class scope through editable save payload', () => {
  it('restores multiple classes and slots as one card, keeping each saved identity', () => {
    const entries = [11, 12].flatMap((classId, c) => [1, 2].map((dayOfWeek, d) => ({
      id: 100 + c * 2 + d, params: { classId, dayOfWeek, periodNo: 3 }, groupKey: 'shared',
    })))
    const draft = load(entries)
    const row = draft.rows.value[0]!
    expect(row.entries).toHaveLength(1)
    expect(row.entries[0]!.slots).toHaveLength(2)
    expect(plannedEntries(row).map(({ id, params }) => ({ id, params })))
      .toEqual(expect.arrayContaining(entries.map(({ id, params }) => ({ id, params }))))
    expect(plannedEntries(row)).toHaveLength(4)
  })
  it('does not turn an emptied selected-class list into a school-wide rule', () => {
    const draft = load([{ id: 1, params: { classId: 11, dayOfWeek: 1, periodNo: 3 }, groupKey: 'a' }])
    const row = draft.rows.value[0]!
    draft.updateEntryValue(type.code, row.entries[0]!.key, 'classIds', [])
    expect(plannedEntries(row).every((entry) => !entry.complete)).toBe(true)
    expect(draft.warnings().join('')).toContain('班级')
  })
  it('keeps global scope explicit and permits independent overlapping groups', () => {
    const draft = load(['a', 'b'].map((groupKey, i) => ({
      id: i + 1, params: { dayOfWeek: 1, periodNo: 3 }, groupKey,
    })))
    const row = draft.rows.value[0]!
    expect(row.entries).toHaveLength(2)
    expect(plannedEntries(row).every((entry) => entry.complete && !('classId' in entry.params))).toBe(true)
    expect(duplicateMessages(row)).toEqual({})
  })
})
