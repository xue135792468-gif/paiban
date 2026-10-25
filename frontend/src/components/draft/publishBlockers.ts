import type { DraftPublishBlocker } from './types'

/**
 * What the page offers next to one blocker. The server sends the action identifier
 * (`ASSIGN_ROOMS`, `EDIT_LESSONS`, …) so the wording and route live here only; the
 * blocker `code` is never translated into an action client-side.
 */
export type BlockerTarget =
  | { kind: 'link'; label: string; to: string }
  | { kind: 'drawer'; label: string }
  | { kind: 'none' }

const NONE: BlockerTarget = { kind: 'none' }

const TARGETS: Record<string, BlockerTarget> = {
  // Ticket 07 turns this into a one-click batch action; until then the room list is
  // the entry point, next to the per-lesson room picker in the grid.
  ASSIGN_ROOMS: { kind: 'link', label: '去教室管理准备教室', to: '/admin/rooms' },
  EDIT_LESSONS: { kind: 'drawer', label: '查看未排课节' },
  EDIT_PERIODS: { kind: 'link', label: '去学期与作息检查', to: '/admin/semesters' },
  OPEN_SUBSTITUTIONS: { kind: 'link', label: '去调代流程', to: '/substitutions' },
  VIEW_TIMETABLE: { kind: 'link', label: '查看已发布课表', to: '/timetable' },
}

export function blockerTarget(action: string): BlockerTarget {
  return TARGETS[action] ?? NONE
}

/** True when the whole-draft check says nothing stands between the draft and publishing. */
export function isPublishable(blockers: DraftPublishBlocker[]): boolean {
  return blockers.length === 0
}
