import { describe, expect, it, vi } from 'vitest'
import { useSwapFlow } from './useSwapFlow'
import type { useDraftAdjust } from './useDraftAdjust'
import type { DraftLesson, SwapCandidate } from '@/components/draft/types'

function fakeAdjust(): ReturnType<typeof useDraftAdjust> {
  return {
    loadSwapCandidates: vi.fn(async () => null),
    swap: vi.fn(),
  } as unknown as ReturnType<typeof useDraftAdjust>
}

function fakeNotify() {
  return { warning: vi.fn(), error: vi.fn() }
}

function candidate(overrides: Partial<SwapCandidate> = {}): SwapCandidate {
  return {
    lessonId: 2,
    courseId: 3,
    teacherId: 4,
    classId: 5,
    periodId: 6,
    available: true,
    conflicts: [],
    warnings: [],
    ...overrides,
  }
}

describe('useSwapFlow', () => {


  it('cross-class entry requests only cross-class candidates and hides the tabs', () => {
    const adjust = fakeAdjust()
    const flow = useSwapFlow({ adjust, notify: fakeNotify() })
    const lesson = { id: 11, periodId: 100 } as DraftLesson

    flow.selectLesson(lesson)
    flow.openCrossClassForSelected()

    expect(adjust.loadSwapCandidates).toHaveBeenCalledWith(11, false, 'CROSS_CLASS')
    expect(flow.singleGroup.value).toBe(true)
    expect(flow.initialGroup.value).toBe('CROSS_CLASS')
  })

  it('confirm passes the current draft version to the swap request', async () => {
    const adjust = fakeAdjust()
    const swap = vi.fn(async () => ({
      saved: true,
      type: 'INTRA_CLASS' as const,
      conflicts: [],
      warnings: [],
    }))
    adjust.swap = swap as unknown as typeof adjust.swap
    const flow = useSwapFlow({ adjust, notify: fakeNotify(), getVersion: () => 9 })
    const lesson = { id: 11, periodId: 100 } as DraftLesson
    const picked = candidate()

    flow.openPanel(lesson)
    flow.selectCandidate(picked)
    const outcome = await flow.confirm()

    expect(outcome.saved).toBe(true)
    expect(swap).toHaveBeenCalledWith(lesson, picked, 9)
  })
})
