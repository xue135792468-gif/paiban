import { describe, expect, it } from 'vitest'
import { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintConfigDto, ConstraintTypeDef } from './types'

describe('weekly requirement identity after a page reload', () => {
  it('keeps a newly added fourth requirement separate from three saved requirements', () => {
    const type: ConstraintTypeDef = {
      code: 'CLASS_UNAVAILABLE', name: '每周不上课时段', category: 'PEDAGOGY', categoryLabel: '教学规律',
      description: '', defaultHard: true, defaultWeight: 100, hardOnly: true, slotGrouped: true,
      params: [
        { name: 'classId', label: '班级', type: 'ID', required: false },
        { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
        { name: 'periodNo', label: '节次', type: 'PERIOD', required: true },
      ],
    }
    // These are exactly the group keys created by adding three cards in a fresh page.
    const saved: ConstraintConfigDto[] = [{ type: type.code, params: {}, enabled: true, hard: true, weight: 100,
      entries: [3, 6, 9].map((key, index) => ({
        id: index + 1, params: { classId: 21, dayOfWeek: 1, periodNo: index + 1 },
        groupKey: `group-${key}`, enabled: true, hard: true,
      })),
    }]
    const draft = useConstraintDraft()
    draft.hydrate([type], saved)
    draft.addEntry(type.code)
    const row = draft.rows.value[0]!
    const added = row.entries.at(-1)!
    draft.updateEntryValue(type.code, added.key, 'classScope', 'SELECTED')
    draft.updateEntryValue(type.code, added.key, 'classIds', [21])
    draft.updateEntryValue(type.code, added.key, 'dayOfWeek', 1)
    draft.updateEntryValue(type.code, added.key, 'periodNo', 4)
    expect(row.entries).toHaveLength(4)
    // Four independent requirements: every card owns its own identity.
    expect(new Set(row.entries.map((entry) => entry.groupKey)).size).toBe(4)

    const payload = draft.payload()
    const savedEntries = payload.find((config) => config.type === type.code)!.entries!
    expect(savedEntries).toHaveLength(4)
    expect(new Set(savedEntries.map((entry) => entry.groupKey)).size).toBe(4)

    const reloaded = useConstraintDraft()
    reloaded.hydrate([type], payload as ConstraintConfigDto[])
    expect(reloaded.rows.value[0]!.entries).toHaveLength(4)
    expect(new Set(reloaded.rows.value[0]!.entries.map((entry) => entry.groupKey)).size).toBe(4)
    // The fourth requirement keeps its own slot and class after the round trip.
    const reloadedEntry = reloaded.rows.value[0]!.entries
      .find((entry) => entry.slots?.[0]?.periodNo === 4)
    expect(reloadedEntry?.values.classIds).toEqual([21])
  })
})
