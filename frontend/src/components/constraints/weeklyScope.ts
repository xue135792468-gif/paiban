import type { ConstraintEntryDto, EntryDraft } from './types'

/**
 * The one place that recognizes the weekly no-class type. Everything else in the
 * constraint page asks this instead of comparing the code itself, so a rename or a
 * second weekly type cannot leave a stray literal behind.
 */
export function isWeeklyNoClass(row: { code: string }): boolean {
  return row.code === 'CLASS_UNAVAILABLE'
}

/**
 * The classes one weekly no-class card targets, or null for the whole school:
 * - an explicit `ALL` is the whole school, including classes created later;
 * - an explicit `SELECTED` is only ever the class list — while it is still empty the
 *   card is "待选班级" and must never widen to whole school;
 * - a legacy payload without an explicit scope keeps its old meaning: the class list
 *   when present, otherwise its scalar `classId`, otherwise the whole school.
 */
export function weeklyClasses(entry: EntryDraft): number[] | null {
  const scope = entry.values.classScope
  if (scope === 'ALL') return null
  if (scope === 'SELECTED') {
    return Array.isArray(entry.values.classIds) ? (entry.values.classIds as number[]) : []
  }
  if (Array.isArray(entry.values.classIds)) return entry.values.classIds as number[]
  return entry.values.classId == null ? null : [Number(entry.values.classId)]
}

export function restoreWeeklyCards(entries: ConstraintEntryDto[], nextKey: (prefix: string) => string): EntryDraft[] {
  const cards = new Map<string, EntryDraft>()
  entries.forEach((entry, index) => {
    const params = entry.params
    const group = entry.groupKey || `legacy-${index}`
    let card = cards.get(group)
    if (!card) {
      card = {
        key: nextKey('entry'), id: null, groupKey: entry.groupKey || nextKey('group'),
        values: { classScope: params.classId == null ? 'ALL' : 'SELECTED', classIds: [] },
        slots: [], enabled: entry.enabled ?? true, hard: entry.hard ?? null, weight: entry.weight ?? null,
      }
      cards.set(group, card)
    }
    if (params.classId != null) {
      const ids = card.values.classIds as number[]
      if (!ids.includes(Number(params.classId))) ids.push(Number(params.classId))
    }
    let slot = card.slots!.find((item) => item.dayOfWeek === params.dayOfWeek && item.periodNo === params.periodNo)
    if (!slot) {
      slot = { key: nextKey('slot'), id: null, dayOfWeek: params.dayOfWeek as number,
        periodNo: params.periodNo as number, classEntryIds: {} }
      card.slots!.push(slot)
    }
    slot.classEntryIds![String(params.classId ?? 'ALL')] = entry.id
  })
  return Array.from(cards.values())
}
