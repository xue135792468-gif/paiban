import type { ConstraintEntryDto, EntryDraft } from './types'

/** Legacy scalar selections remain selected; only an explicit global scope is global. */
export function weeklyClasses(entry: EntryDraft): number[] | null {
  if (entry.values.classScope === 'ALL') return null
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
