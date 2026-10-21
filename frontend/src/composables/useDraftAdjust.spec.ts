import { describe, expect, it, vi } from 'vitest'
import { useDraftAdjust, type DraftAdjustClient } from './useDraftAdjust'

interface PatchCall {
  url: string
  body: { periodId: number | null; roomId: number | null }
}

interface PostCall {
  url: string
  body: unknown
}

interface GetCall {
  url: string
  config?: { params?: Record<string, unknown> }
}

function fakeClient(reply?: (url: string, body: PatchCall['body']) => Promise<any>) {
  const calls: PatchCall[] = []
  const posts: PostCall[] = []
  const gets: GetCall[] = []
  const client: DraftAdjustClient = {
    get: vi.fn(async (url: string, config?: GetCall['config']) => {
      gets.push({ url, config })
      return { data: { lessonId: 1, roomId: null, targets: [], intraClass: [], crossClass: [] } }
    }),
    patch: vi.fn(async (url: string, body: PatchCall['body']) => {
      calls.push({ url, body })
      if (reply) return reply(url, body)
      return { data: { saved: true, valid: true, conflicts: [] } }
    }),
    post: vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return { data: { saved: true, valid: true, hardConflicts: 0, type: 'INTRA_CLASS', conflicts: [], warnings: [] } }
    }),
  }
  return { client, calls, posts, gets }
}

function fakeNotify() {
  return { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}

describe('useDraftAdjust', () => {
  it('undo sends the lesson back to its original period and room', async () => {
    const { client, calls } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move({ id: 11, periodId: 100, roomId: 5 }, { periodId: 200 })
    expect(adjust.undoCount.value).toBe(1)

    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(true)
    expect(calls).toEqual([
      { url: '/timetable-drafts/7/lessons/11', body: { periodId: 200, roomId: 5 } },
      { url: '/timetable-drafts/7/lessons/11', body: { periodId: 100, roomId: 5 } },
    ])
    expect(adjust.canUndo.value).toBe(false)
  })

  it('keeps at most 20 steps and drops the oldest', async () => {
    const { client, calls } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    for (let index = 1; index <= 25; index += 1) {
      await adjust.move({ id: index, periodId: index * 10, roomId: null }, { periodId: index * 10 + 1 })
    }

    expect(adjust.undoCount.value).toBe(20)

    for (let step = 0; step < 20; step += 1) {
      expect((await adjust.undo()).undone).toBe(true)
    }

    expect(adjust.canUndo.value).toBe(false)
    expect(calls[25].url).toBe('/timetable-drafts/7/lessons/25')
    expect(calls[25].body.periodId).toBe(250)
    expect(calls.slice(26).some((call) => call.url.endsWith('/lessons/5'))).toBe(false)
    expect((await adjust.undo()).undone).toBe(false)
  })

  it('does not push when the server rejects the move', async () => {
    const conflict = { type: 'CLASS_CONFLICT', message: '本班已有课', lessonId: 11 }
    const { client } = fakeClient(async () => ({ data: { saved: false, conflicts: [conflict] } }))
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.move({ id: 11, periodId: 100, roomId: null }, { periodId: 200 })

    expect(outcome.saved).toBe(false)
    expect(outcome.conflicts).toEqual([conflict])
    expect(adjust.canUndo.value).toBe(false)
  })

  it('keeps the stack when the server rejects the undo', async () => {
    const notify = fakeNotify()
    let undoAttempts = 0
    const { client } = fakeClient(async () => {
      undoAttempts += 1
      if (undoAttempts === 1) return { data: { saved: true, conflicts: [] } }
      return {
        data: { saved: false, conflicts: [{ type: 'CLASS_CONFLICT', message: '已变化', lessonId: 11 }] },
      }
    })
    const adjust = useDraftAdjust(7, { client, notify })

    await adjust.move({ id: 11, periodId: 100, roomId: null }, { periodId: 200 })
    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(false)
    expect(adjust.undoCount.value).toBe(1)
    expect(notify.error).toHaveBeenCalled()
  })

  it('keeps the stack when the undo request fails', async () => {
    const notify = fakeNotify()
    let calls = 0
    const { client } = fakeClient(async () => {
      calls += 1
      if (calls === 1) return { data: { saved: true, conflicts: [] } }
      throw new Error('network down')
    })
    const adjust = useDraftAdjust(7, { client, notify })

    await adjust.move({ id: 11, periodId: 100, roomId: null }, { periodId: 200 })
    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(false)
    expect(adjust.undoCount.value).toBe(1)
    expect(notify.error).toHaveBeenCalled()
  })

  it('maps move-targets by period id', async () => {
    const conflict = { type: 'TEACHER_CONFLICT', message: '教师冲突', lessonId: 11 }
    const client: DraftAdjustClient = {
      get: vi.fn(async () => ({
        data: {
          lessonId: 11,
          roomId: 5,
          targets: [
            { periodId: 100, available: true, conflicts: [] },
            { periodId: 200, available: false, conflicts: [conflict] },
          ],
        },
      })),
      patch: vi.fn(),
      post: vi.fn(),
    }
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const targets = await adjust.loadMoveTargets(11)

    expect(targets[100]).toEqual({ available: true, conflicts: [] })
    expect(targets[200]).toEqual({ available: false, conflicts: [conflict] })
  })

  it('undoes a lesson dragged in from the unassigned drawer by clearing its period', async () => {
    const { client, calls } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move({ id: 11, periodId: null, roomId: null }, { periodId: 200 })
    expect(adjust.undoCount.value).toBe(1)

    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(true)
    expect(calls).toEqual([
      { url: '/timetable-drafts/7/lessons/11', body: { periodId: 200, roomId: null } },
      { url: '/timetable-drafts/7/lessons/11', body: { periodId: null, roomId: null } },
    ])
    expect(adjust.canUndo.value).toBe(false)
  })

  it('swap posts the pair once and records a swap undo entry', async () => {
    const { client, posts } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.swap({ id: 11 }, { lessonId: 12 }, true)

    expect(posts).toEqual([
      { url: '/timetable-drafts/7/swap', body: { lessonId: 11, targetLessonId: 12, swapRooms: true } },
    ])
    expect(outcome.saved).toBe(true)
    expect(outcome.type).toBe('INTRA_CLASS')
    expect(adjust.undoCount.value).toBe(1)
    expect(adjust.undoStack.value[0]).toEqual({
      kind: 'swap',
      lessonId: 11,
      targetLessonId: 12,
      swapRooms: true,
    })
  })

  it('undoing a swap runs the same swap again', async () => {
    const { client, posts } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.swap({ id: 11 }, { lessonId: 12 }, false)
    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(true)
    expect(posts).toHaveLength(2)
    expect(posts[1]).toEqual({
      url: '/timetable-drafts/7/swap',
      body: { lessonId: 11, targetLessonId: 12, swapRooms: false },
    })
    expect(adjust.canUndo.value).toBe(false)
  })

  it('does not record a swap the server rejected and returns the conflicts', async () => {
    const conflict = { type: 'TEACHER_SUBJECT_MISMATCH', message: '学科不一致', lessonId: 11 }
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return {
        data: {
          saved: false,
          valid: false,
          hardConflicts: 1,
          type: 'CROSS_CLASS',
          conflicts: [conflict],
          warnings: ['教师学科未维护'],
        },
      }
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.swap({ id: 11 }, { lessonId: 12 }, false)

    expect(outcome.saved).toBe(false)
    expect(outcome.conflicts).toEqual([conflict])
    expect(outcome.warnings).toEqual(['教师学科未维护'])
    expect(adjust.canUndo.value).toBe(false)
  })

  it('distinguishes move and swap entries and undoes them in order', async () => {
    const { client, calls, posts } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move({ id: 11, periodId: 100, roomId: null }, { periodId: 200 })
    await adjust.swap({ id: 11 }, { lessonId: 12 }, false)

    expect(adjust.undoStack.value.map((entry) => entry.kind)).toEqual(['move', 'swap'])

    await adjust.undo()
    expect(posts).toHaveLength(2)
    expect(calls).toHaveLength(1)

    await adjust.undo()
    expect(calls).toHaveLength(2)
    expect(calls[1].body).toEqual({ periodId: 100, roomId: null })
  })

  it('keeps the swap entry when the undo swap is rejected', async () => {
    const notify = fakeNotify()
    let attempts = 0
    const { client } = fakeClient()
    client.post = vi.fn(async () => {
      attempts += 1
      if (attempts === 1) {
        return { data: { saved: true, valid: true, type: 'INTRA_CLASS', conflicts: [], warnings: [] } }
      }
      return {
        data: {
          saved: false,
          valid: false,
          type: 'INTRA_CLASS',
          conflicts: [{ type: 'CLASS_CONFLICT', message: '已变化', lessonId: 11 }],
          warnings: [],
        },
      }
    })
    const adjust = useDraftAdjust(7, { client, notify })

    await adjust.swap({ id: 11 }, { lessonId: 12 }, false)
    const outcome = await adjust.undo()

    expect(outcome.undone).toBe(false)
    expect(adjust.undoCount.value).toBe(1)
    expect(notify.error).toHaveBeenCalled()
  })

  it('loads swap candidates with the availability flag and normalises the groups', async () => {
    const { client, gets } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const response = await adjust.loadSwapCandidates(11, true)

    expect(gets).toEqual([
      {
        url: '/timetable-drafts/7/lessons/11/swap-candidates',
        config: { params: { type: 'ALL', includeUnavailable: true } },
      },
    ])
    expect(response).toEqual({ lessonId: 1, intraClass: [], crossClass: [] })
  })
})
