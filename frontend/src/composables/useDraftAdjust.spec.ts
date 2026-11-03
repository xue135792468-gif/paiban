import { describe, expect, it, vi } from 'vitest'
import { useDraftAdjust, type DraftAdjustClient } from './useDraftAdjust'
import type { DraftLesson } from '@/components/draft/types'

interface PatchCall {
  url: string
  body: { periodId: number | null; roomId: number | null; expectedVersion?: number }
}

interface PostCall {
  url: string
  body: unknown
}

interface GetCall {
  url: string
  config?: { params?: Record<string, unknown> }
}

function fakeClient(
  reply?: (url: string, body: PatchCall['body']) => Promise<any>,
  postReply?: (url: string, body: unknown) => Promise<any>,
) {
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
      if (postReply) return postReply(url, body)
      return { data: { saved: true, valid: true, hardConflicts: 0, type: 'INTRA_CLASS', conflicts: [], warnings: [] } }
    }),
  }
  return { client, calls, posts, gets }
}

function fakeNotify() {
  return { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}

describe('useDraftAdjust', () => {
  it('consecutive legacy undos use the version each earlier undo produced', async () => {
    const versions = [2, 3, 4, 5]
    let postCalls = 0
    const { client, posts } = fakeClient(undefined, async () => ({
      data: {
        saved: true,
        valid: true,
        version: versions[postCalls++],
        type: 'INTRA_CLASS',
        conflicts: [],
        warnings: [],
      },
    }))
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.swap({ id: 11 }, { lessonId: 12 }, false)
    await adjust.swap({ id: 13 }, { lessonId: 14 }, false)
    expect((await adjust.undo()).undone).toBe(true)
    expect(posts[2].body).toMatchObject({ expectedVersion: 3 })
    expect((await adjust.undo()).undone).toBe(true)
    expect(posts[3].body).toMatchObject({ expectedVersion: 4 })
  })

  it('legacy move and swap undo send the version produced by that write', async () => {
    const { client, calls, posts } = fakeClient(
      async () => ({ data: { saved: true, valid: true, version: 5, conflicts: [] } }),
      async () => ({
        data: { saved: true, valid: true, version: 9, type: 'INTRA_CLASS', conflicts: [], warnings: [] },
      }),
    )
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move({ id: 11, periodId: 100, roomId: 5 }, { periodId: 200 })
    await adjust.undo()
    expect(calls[1].body.expectedVersion).toBe(5)

    await adjust.swap({ id: 11 }, { lessonId: 12 }, false)
    await adjust.undo()
    expect(posts[1].body).toMatchObject({ expectedVersion: 9 })
  })

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

function changeReply(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      saved: true,
      valid: true,
      version: 5,
      hardConflicts: 0,
      softPenaltyBefore: 0,
      softPenaltyAfter: 0,
      softPenaltyDelta: 0,
      addedConflicts: [],
      remainingConflicts: [],
      conflicts: [],
      ...overrides,
    },
  }
}

function draftLesson(overrides: Partial<DraftLesson> & { id: number }): DraftLesson {
  return {
    assignmentId: overrides.id,
    courseId: 1,
    courseName: '数学',
    teacherId: 9,
    teacherName: '张老师',
    classId: 3,
    studentCount: 40,
    periodId: 100,
    roomId: 5,
    locked: false,
    ...overrides,
  }
}

describe('useDraftAdjust atomic change sets', () => {
  it('moveTo sends one /changes item with source guards and pushes the inverse', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return changeReply()
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.moveTo(
      { id: 11, periodId: 100, roomId: 5, teacherId: 9 },
      200,
      4,
      'fp-1',
    )

    expect(outcome.saved).toBe(true)
    expect(posts).toEqual([
      {
        url: '/timetable-drafts/7/changes',
        body: {
          changes: [
            {
              lessonId: 11,
              toPeriodId: 200,
              toTeacherId: null,
              toRoomId: null,
              expectedPeriodId: 100,
              expectedTeacherId: 9,
              expectedRoomId: 5,
            },
          ],
          expectedVersion: 4,
          rulesFingerprint: 'fp-1',
          verifySource: true,
        },
      },
    ])
    expect(adjust.undoStack.value).toEqual([
      {
        kind: 'changes',
        label: '已移动课节',
        items: [
          {
            lessonId: 11,
            toPeriodId: 100,
            toTeacherId: 9,
            toRoomId: 5,
            expectedPeriodId: 200,
            expectedTeacherId: 9,
            expectedRoomId: 5,
          },
        ],
      },
    ])
  })

  it('swapLessons exchanges only the periods and records one undo step', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return changeReply({ version: 6 })
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.swapLessons(
      { id: 11, periodId: 100, roomId: 5, teacherId: 9 },
      { id: 12, periodId: 200, roomId: 6, teacherId: 10 },
      5,
      'fp-2',
    )

    expect(posts[0].body).toEqual({
      changes: [
        {
          lessonId: 11,
          toPeriodId: 200,
          toTeacherId: null,
          toRoomId: null,
          expectedPeriodId: 100,
          expectedTeacherId: 9,
          expectedRoomId: 5,
        },
        {
          lessonId: 12,
          toPeriodId: 100,
          toTeacherId: null,
          toRoomId: null,
          expectedPeriodId: 200,
          expectedTeacherId: 10,
          expectedRoomId: 6,
        },
      ],
      expectedVersion: 5,
      rulesFingerprint: 'fp-2',
      verifySource: true,
    })
    expect(adjust.undoCount.value).toBe(1)
    expect(adjust.undoStack.value[0]).toMatchObject({ kind: 'changes', label: '已交换两节课' })
  })

  it('applyPlan posts the token and records every inverse move as one step', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return changeReply({ version: 8 })
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })
    const lessons = [
      draftLesson({ id: 11, periodId: 200, roomId: 5, teacherId: 9 }),
      draftLesson({ id: 12, periodId: 300, roomId: 6, teacherId: 10 }),
    ]
    const plan = {
      token: 'tok-1',
      kind: 'CYCLE' as const,
      moves: [
        { lessonId: 11, fromPeriodId: 100, toPeriodId: 200, teacherId: 9, courseId: 1, classId: 3 },
        { lessonId: 12, fromPeriodId: 200, toPeriodId: 300, teacherId: 10, courseId: 2, classId: 3 },
      ],
      movedLessons: 2,
      affectedTeachers: 2,
      affectedDays: 1,
      softPenaltyAfter: 0,
      softPenaltyDelta: 0,
      expiresAt: '2026-01-01T00:00:00Z',
    }

    const outcome = await adjust.applyPlan(plan, 7, lessons)

    expect(outcome.saved).toBe(true)
    expect(posts[0]).toEqual({
      url: '/timetable-drafts/7/cycle-plans/apply',
      body: { token: 'tok-1', expectedVersion: 7 },
    })
    expect(adjust.undoStack.value).toEqual([
      {
        kind: 'changes',
        label: '联动方案',
        items: [
          {
            lessonId: 11,
            toPeriodId: 100,
            toTeacherId: 9,
            toRoomId: 5,
            expectedPeriodId: 200,
            expectedTeacherId: 9,
            expectedRoomId: 5,
          },
          {
            lessonId: 12,
            toPeriodId: 200,
            toTeacherId: 10,
            toRoomId: 6,
            expectedPeriodId: 300,
            expectedTeacherId: 10,
            expectedRoomId: 6,
          },
        ],
      },
    ])
  })

  it('undo sends the whole inverse change set with the current version and pops on success', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return changeReply({ version: 5 + posts.length })
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.moveTo({ id: 11, periodId: 100, roomId: 5, teacherId: 9 }, 200, 4, 'fp-1')
    const outcome = await adjust.undo({ expectedVersion: 5, rulesFingerprint: 'fp-current' })

    expect(outcome.undone).toBe(true)
    expect(posts[1]).toEqual({
      url: '/timetable-drafts/7/changes',
      body: {
        changes: [
          {
            lessonId: 11,
            toPeriodId: 100,
            toTeacherId: 9,
            toRoomId: 5,
            expectedPeriodId: 200,
            expectedTeacherId: 9,
            expectedRoomId: 5,
          },
        ],
        expectedVersion: 5,
        rulesFingerprint: 'fp-current',
        verifySource: true,
      },
    })
    expect(adjust.canUndo.value).toBe(false)
  })

  it('keeps the change-set undo entry when the undo answers 409 and surfaces the reason', async () => {
    const notify = fakeNotify()
    let calls = 0
    const { client } = fakeClient()
    client.post = vi.fn(async () => {
      calls += 1
      if (calls === 1) return changeReply()
      throw { response: { status: 409, data: { message: '该课节已锁定，请先解锁' } } }
    })
    const adjust = useDraftAdjust(7, { client, notify })

    await adjust.moveTo({ id: 11, periodId: 100, roomId: 5, teacherId: 9 }, 200, 4, 'fp-1')
    const outcome = await adjust.undo({ expectedVersion: 5, rulesFingerprint: 'fp-1' })

    expect(outcome.undone).toBe(false)
    expect(adjust.undoCount.value).toBe(1)
    expect(notify.error).toHaveBeenCalledWith('该课节已锁定，请先解锁')
  })


  it('does not record a change set the server refuses and exposes its added conflicts', async () => {
    const conflict = {
      key: 'k',
      code: 'CLASS_CONFLICT',
      message: '本班已有课',
      lessonId: 11,
      severity: 1,
    }
    const { client } = fakeClient()
    client.post = vi.fn(async () =>
      changeReply({ saved: false, version: 4, addedConflicts: [conflict], conflicts: [conflict] }),
    )
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.moveTo(
      { id: 11, periodId: 100, roomId: 5, teacherId: 9 },
      200,
      4,
      'fp-1',
    )

    expect(outcome.saved).toBe(false)
    expect(outcome.addedConflicts).toEqual([conflict])
    expect(outcome.conflicts).toEqual([
      { type: 'CLASS_CONFLICT', message: '本班已有课', lessonId: 11 },
    ])
    expect(adjust.canUndo.value).toBe(false)
  })

  it('keeps at most 20 atomic steps', async () => {
    const { client } = fakeClient()
    client.post = vi.fn(async () => changeReply())
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    for (let index = 1; index <= 25; index += 1) {
      await adjust.moveTo(
        { id: index, periodId: index * 10, roomId: null, teacherId: 9 },
        index * 10 + 1,
        4,
        'fp',
      )
    }

    expect(adjust.undoCount.value).toBe(20)
    expect(adjust.undoStack.value[0]).toMatchObject({
      kind: 'changes',
      items: [{ lessonId: 6 }],
    })
  })

  it('returns readable search results and leaves the stack untouched', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return {
        data: {
          status: 'NONE',
          message: '当前范围和上限内没有可用方案',
          version: 4,
          fingerprint: 'fp',
          plans: [],
        },
      }
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const result = await adjust.searchPlans(11, 200, 12, 4, 'fp')

    expect(result?.status).toBe('NONE')
    expect(posts[0].url).toBe('/timetable-drafts/7/cycle-plans/search')
    expect(adjust.canUndo.value).toBe(false)
  })
})

describe('useDraftAdjust stale-safe legacy writes', () => {
  it('swap sends expectedVersion when the editor knows the draft version', async () => {
    const { client, posts } = fakeClient()
    client.post = vi.fn(async (url: string, body: unknown) => {
      posts.push({ url, body })
      return { data: { saved: true, valid: true, type: 'INTRA_CLASS', conflicts: [], warnings: [] } }
    })
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    const outcome = await adjust.swap({ id: 11 }, { lessonId: 12 }, false, 7)

    expect(outcome.saved).toBe(true)
    expect(posts).toEqual([
      {
        url: '/timetable-drafts/7/swap',
        body: {
          lessonId: 11,
          targetLessonId: 12,
          swapRooms: false,
          expectedVersion: 7,
        },
      },
    ])
  })

  it('move can also send expectedVersion without changing the old two-argument call', async () => {
    const { client, calls } = fakeClient()
    const adjust = useDraftAdjust(7, { client, notify: fakeNotify() })

    await adjust.move(
      { id: 11, periodId: 100, roomId: 5, teacherId: 9 },
      { periodId: 200 },
      7,
    )

    expect(calls[0]).toEqual({
      url: '/timetable-drafts/7/lessons/11',
      body: { periodId: 200, roomId: 5, expectedVersion: 7 },
    })
  })

  it('returns a readable rejection on 409 and never records an undo entry', async () => {
    const notify = fakeNotify()
    const { client } = fakeClient()
    client.post = vi.fn(async () => {
      throw { response: { status: 409, data: { message: '课表已被其他人修改，请刷新后重试' } } }
    })
    const adjust = useDraftAdjust(7, { client, notify })

    const outcome = await adjust.swap({ id: 11 }, { lessonId: 12 }, false, 7)

    expect(outcome.saved).toBe(false)
    expect(outcome.error).toBe('课表已被其他人修改，请刷新后重试')
    expect(notify.error).toHaveBeenCalledWith('课表已被其他人修改，请刷新后重试')
    expect(adjust.canUndo.value).toBe(false)
  })
})
