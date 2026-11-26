import type { DraftPublishBlocker, DraftValidation } from './types'

/**
 * What the page offers next to one blocker. The server sends the action identifier
 * (`EDIT_LESSONS`, …) so the wording and route live here only; the blocker `code` is
 * never translated into an action client-side.
 */
export type BlockerTarget =
  | { kind: 'link'; label: string; to: string }
  | { kind: 'drawer'; label: string }
  | { kind: 'dialog'; label: string }
  | { kind: 'none' }

const NONE: BlockerTarget = { kind: 'none' }

const TARGETS: Record<string, BlockerTarget> = {
  EDIT_LESSONS: { kind: 'drawer', label: '查看未排课节' },
  EDIT_PERIODS: { kind: 'link', label: '去学期与作息检查', to: '/admin/semesters' },
  OPEN_SUBSTITUTIONS: { kind: 'link', label: '去调代流程', to: '/substitutions' },
  VIEW_TIMETABLE: { kind: 'link', label: '查看已发布课表', to: '/timetable' },
}

export type BlockerAction = BlockerTarget | { kind: 'locate-conflict'; label: string }

/** Resolves the server's action identifier into the next step the page offers. */
export function blockerTarget(action: string): BlockerTarget {
  return TARGETS[action] ?? NONE
}

/** Resolves one blocker into a UI action, including client-side conflict navigation. */
export function blockerActionFor(blocker: DraftPublishBlocker): BlockerAction {
  if (blocker.code === 'HARD_CONFLICTS') {
    return { kind: 'locate-conflict', label: '逐条定位' }
  }
  return blockerTarget(blocker.action)
}

/** True when the whole-draft check says nothing stands between the draft and publishing. */
export function isPublishable(blockers: DraftPublishBlocker[]): boolean {
  return blockers.length === 0
}

/**
 * The publish button's disabled state. A check that has not come back yet is not a green
 * light: only a validation result with no blockers enables publishing.
 */
export function canPublishNow(validation: DraftValidation | null): boolean {
  return validation !== null && isPublishable(validation.blockers)
}
