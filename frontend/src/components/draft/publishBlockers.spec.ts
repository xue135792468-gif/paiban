import { describe, expect, it } from 'vitest'
import { blockerTarget, canPublishNow, isPublishable } from './publishBlockers'
import type { DraftPublishBlocker, DraftValidation } from './types'

function blocker(overrides: Partial<DraftPublishBlocker> = {}): DraftPublishBlocker {
  return { code: 'UNSCHEDULED_LESSONS', message: '还有 3 节课未排课', count: 3, action: 'EDIT_LESSONS', ...overrides }
}

describe('blockerTarget', () => {
  it('已下线动作不再给出任何入口', () => {
    expect(blockerTarget('ASSIGN_ROOMS')).toEqual({ kind: 'none' })
  })

  it('未排课打开本页的未排课抽屉，不跳走', () => {
    expect(blockerTarget('EDIT_LESSONS')).toEqual({ kind: 'drawer', label: '查看未排课节' })
  })

  it('作息引用失效指向学期与作息', () => {
    expect(blockerTarget('EDIT_PERIODS')).toMatchObject({ kind: 'link', to: '/admin/semesters' })
  })

  it('学期已有基线指向调代流程', () => {
    expect(blockerTarget('OPEN_SUBSTITUTIONS')).toMatchObject({ kind: 'link', to: '/substitutions' })
  })

  it('未知或无需动作的标识不给按钮', () => {
    expect(blockerTarget('NONE')).toEqual({ kind: 'none' })
    expect(blockerTarget('SOMETHING_NEW')).toEqual({ kind: 'none' })
  })
})

describe('isPublishable', () => {
  it('没有阻塞项时视为可发布', () => {
    expect(isPublishable([])).toBe(true)
  })

  it('任一阻塞项都挡住发布', () => {
    expect(isPublishable([blocker()])).toBe(false)
  })
})

describe('canPublishNow', () => {
  it('校验还没回来时按钮保持禁用', () => {
    expect(canPublishNow(null)).toBe(false)
  })

  it('校验回来且没有阻塞项时才允许发布', () => {
    expect(canPublishNow(validation([]))).toBe(true)
    expect(canPublishNow(validation([blocker()]))).toBe(false)
  })
})

function validation(blockers: DraftPublishBlocker[]): DraftValidation {
  return { valid: blockers.length === 0, hardConflicts: 0, conflicts: [], publishable: blockers.length === 0, blockers }
}
