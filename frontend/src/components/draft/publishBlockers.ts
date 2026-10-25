import type { DraftPublishBlocker, DraftValidation } from './types'

/**
 * What the page offers next to one blocker. The server sends the action identifier
 * (`ASSIGN_ROOMS`, `EDIT_LESSONS`, …) so the wording and route live here only; the
 * blocker `code` is never translated into an action client-side.
 */
export type BlockerTarget =
  | { kind: 'link'; label: string; to: string }
  | { kind: 'drawer'; label: string }
  | { kind: 'dialog'; label: string }
  | { kind: 'none' }

const NONE: BlockerTarget = { kind: 'none' }

const TARGETS: Record<string, BlockerTarget> = {
  // Ticket 07: one explicit action gives every class with missing rooms a fixed
  // ordinary room.
  ASSIGN_ROOMS: { kind: 'dialog', label: '按行政班补固定教室' },
  EDIT_LESSONS: { kind: 'drawer', label: '查看未排课节' },
  EDIT_PERIODS: { kind: 'link', label: '去学期与作息检查', to: '/admin/semesters' },
  OPEN_SUBSTITUTIONS: { kind: 'link', label: '去调代流程', to: '/substitutions' },
  VIEW_TIMETABLE: { kind: 'link', label: '查看已发布课表', to: '/timetable' },
}

const ROOM_MANAGEMENT: BlockerTarget = {
  kind: 'link',
  label: '去教室管理准备教室',
  to: '/admin/rooms',
}

/**
 * Resolves the server's action identifier. Without both permissions the fill action
 * would be refused, so the room list stays the manual way in instead of offering a
 * button the server will reject.
 */
export function blockerTarget(action: string, options: { roomFill?: boolean } = {}): BlockerTarget {
  if (action === 'ASSIGN_ROOMS' && options.roomFill === false) {
    return ROOM_MANAGEMENT
  }
  return TARGETS[action] ?? NONE
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
