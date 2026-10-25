import { describe, expect, it } from 'vitest'
import { blockerTarget, isPublishable } from './publishBlockers'
import type { DraftPublishBlocker } from './types'

function blocker(overrides: Partial<DraftPublishBlocker> = {}): DraftPublishBlocker {
  return { code: 'MISSING_ROOMS', message: '有 3 节课还没有分配教室', count: 3, action: 'ASSIGN_ROOMS', ...overrides }
}

describe('blockerTarget', () => {
  it('缺教室指向教室管理', () => {
    expect(blockerTarget('ASSIGN_ROOMS')).toEqual({
      kind: 'link',
      label: '去教室管理准备教室',
      to: '/admin/rooms',
    })
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
