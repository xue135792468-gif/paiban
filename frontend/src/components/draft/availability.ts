import type { BlockedClassSlot } from './types'

/** A class's unavailable targets never imply that its teacher or room is unavailable. */
export function blockedPeriodsForClass(
  slots: BlockedClassSlot[], classId: number | null,
): Record<number, string> {
  return Object.fromEntries(slots.filter((slot) => slot.classId === classId)
    .map((slot) => [slot.periodId, slot.reason]))
}
