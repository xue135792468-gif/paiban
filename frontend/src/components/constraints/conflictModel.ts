import { duplicateMessages, scalarConflictsByCard } from './entryChecks'
import { isThresholdType } from './entryModel'
import { emptyObjectNames, namesForField, thresholdConflicts } from './thresholdModel'
import type { ObjectNames, TypeDraft } from './types'
import type { SummaryContext } from './entrySummary'

/** What the page already knows would be refused, in the two shapes it needs it. */
export interface ConflictReport {
  /** Precise messages for the pre-check bar (contradictory scalars, duplicate threshold scopes). */
  messages: string[]
  /** Types to mark red in the left navigation before the save button is even pressed. */
  codes: string[]
}

/**
 * The three pre-save conditions the server would refuse — identical entries, two
 * scalar conditions for one object, two rows for one threshold scope — evaluated in one
 * pass. `messages` and `codes` are two readings of that single result, so the bar and
 * the red dots can never disagree and neither of them re-runs the checks.
 *
 * `names` is keyed by entry field (`teacherId` / `classId` / `courseId`), so a label is
 * always taken from the list the offending field actually uses.
 */
export function conflictReport(
  rows: TypeDraft[],
  names: ObjectNames = emptyObjectNames(),
): ConflictReport {
  const messages = new Set<string>()
  const codes: string[] = []
  const ctx: SummaryContext = { teacherNames: namesForField(names, 'teacherId'), courseNames: names.courseId }
  rows.forEach((row) => {
    let conflicted = false
    // An identical row is refused too; it is already shown on its own card, so it only
    // feeds the red dot and not the bar.
    if (Object.keys(duplicateMessages(row)).length > 0) {
      conflicted = true
    }
    Object.values(scalarConflictsByCard(row, ctx)).forEach((texts) => {
      conflicted = true
      texts.forEach((text) => messages.add(text))
    })
    if (isThresholdType(row)) {
      thresholdConflicts(row, names).forEach((conflict) => {
        conflicted = true
        messages.add(conflict.message)
      })
    }
    if (conflicted) {
      codes.push(row.code)
    }
  })
  return { messages: Array.from(messages), codes }
}
