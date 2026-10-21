import { describe, expect, it, vi } from 'vitest'
import { useDraftAdjust, type DraftAdjustClient } from './useDraftAdjust'

interface PatchCall {
  url: string
  body: { periodId: number; roomId: number | null }
}

function fakeClient(reply?: (url: string, body: PatchCall['body']) => Promise<any>) {
  const calls: PatchCall[] = []
  const client: DraftAdjustClient = {
    get: vi.fn(async () => ({ data: { lessonId: 1, roomId: null, targets: [] } })),
    patch: vi.fn(async (url: string, body: PatchCall['body']) => {
      calls.push({ url, body })
      if (reply) return reply(url, body)
      return { data: { saved: true, valid: true, conflicts: [] } }
    }),
  }
  return { client, calls }
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
    }
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const targets = await adjust.loadMoveTargets(11)

    expect(targets[100]).toEqual({ available: true, conflicts: [] })
    expect(targets[200]).toEqual({ available: false, conflicts: [conflict] })
  })

  it('cannot undo a lesson that came from the unassigned drawer', async () => {
    const { client } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move({ id: 11, periodId: null, roomId: null }, { periodId: 200 })

    expect(adjust.canUndo.value).toBe(false)
  })
})
