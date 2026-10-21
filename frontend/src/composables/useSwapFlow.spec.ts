import { describe, expect, it, vi } from 'vitest'
import { useSwapFlow } from './useSwapFlow'
import type { useDraftAdjust } from './useDraftAdjust'
import type { SwapCandidate } from '@/components/draft/types'

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
  it('turns the room exchange on for a candidate that requires it', () => {
    const flow = useSwapFlow({ adjust: fakeAdjust(), notify: fakeNotify() })

    flow.selectCandidate(candidate({ roomSwapRequired: true }))

    expect(flow.previewVisible.value).toBe(true)
    expect(flow.swapRooms.value).toBe(true)
  })

  it('leaves the room exchange off for an ordinary candidate', () => {
    const flow = useSwapFlow({ adjust: fakeAdjust(), notify: fakeNotify() })

    flow.selectCandidate(candidate({ roomSwapRequired: true }))
    flow.selectCandidate(candidate())

    expect(flow.swapRooms.value).toBe(false)
  })
})
