import { describe, expect, it, vi } from 'vitest'
import { ref, type Ref } from 'vue'
import { useDraftLocks, type DraftLockViewMode, type SetLocksFn } from './useDraftLocks'
import type { DraftDetail, DraftLesson, LockResult } from '@/components/draft/types'

function lesson(id: number, overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: 1,
    courseName: '数学',
    teacherId: 10,
    teacherName: '张老师',
    classId: 1,
    className: '高一1班',
    studentCount: 40,
    periodId: 100 + id,
    dayOfWeek: 1,
    periodNo: id,
    locked: false,
    ...overrides,
  }
}

function detail(lessons: DraftLesson[], version = 7): DraftDetail {
  return {
    draft: {
      id: 5,
      semesterId: 1,
      name: '草稿',
      status: 'DRAFT',
      scoreHard: 0,
      scoreSoft: 0,
      createdAt: '2026-11-02T00:00:00',
      lessonCount: lessons.length,
      version,
    },
    lessons,
    periods: [],
    rooms: [],
    rulesFingerprint: 'fp',
  }
}

interface SetupOptions {
  lessons?: DraftLesson[]
  setLocks?: SetLocksFn
  viewMode?: DraftLockViewMode
  targetId?: number | null
  editable?: boolean
}

function setup(options: SetupOptions = {}) {
  const lessons = options.lessons ?? [
    lesson(1, { periodId: 101, dayOfWeek: 1 }),
    lesson(2, { periodId: 102, dayOfWeek: 1 }),
    lesson(3, { periodId: 103, dayOfWeek: 2 }),
    lesson(4, { periodId: null, dayOfWeek: null, periodNo: null, assignmentId: 9 }),
    lesson(5, { classId: 2, className: '高一2班', periodId: 105, dayOfWeek: 1 }),
  ]
  const detailRef = ref<DraftDetail | null>(detail(lessons))
  const draftEditable = ref(options.editable ?? true)
  const viewMode = ref<DraftLockViewMode>(options.viewMode ?? 'class')
  const targetId = ref<number | null>(options.targetId === undefined ? 1 : options.targetId)
  const reload = vi.fn(async () => {})
  const notify = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
  const setLocks = vi.fn<SetLocksFn>(options.setLocks ?? (async () => ({
    requested: 1,
    updated: 1,
    locked: true,
    version: 8,
  })))
  const locks = useDraftLocks({
    draftId: 5,
    detail: detailRef as Ref<DraftDetail | null>,
    draftEditable,
    viewMode,
    targetId,
    reload,
    notify,
    setLocks,
  })
  return { locks, detailRef, draftEditable, viewMode, targetId, reload, notify, setLocks, lessons }
}

describe('useDraftLocks', () => {
  it('进入/退出多选会清空选择，切换与全选只含当前视图的已排课节', () => {
    const { locks } = setup()

    locks.toggleSelectionMode()
    expect(locks.selectionMode.value).toBe(true)
    locks.toggle(1)
    locks.toggle(2)
    expect(locks.selectedCount.value).toBe(2)
    locks.toggle(1)
    expect(locks.selectedCount.value).toBe(1)
    locks.clearSelection()
    expect(locks.selectedCount.value).toBe(0)

    locks.selectAllVisible()
    // Class 1 has lessons 1, 2, 3; lesson 4 is unassigned and lesson 5 belongs to class 2.
    expect([...locks.selectedIds.value].sort()).toEqual([1, 2, 3])

    locks.toggleSelectionMode()
    expect(locks.selectionMode.value).toBe(false)
    expect(locks.selectedIds.value).toEqual([])
  })

  it('按天/全班只展开当前行政班已排课节，教师视角不允许按范围', () => {
    const { locks, viewMode } = setup()

    expect(locks.canUseClassScope.value).toBe(true)
    expect(locks.scope('day', 1)).toEqual([1, 2])
    expect(locks.scope('day', 2)).toEqual([3])
    expect(locks.scope('class')).toEqual([1, 2, 3])
    expect(locks.classScopeCount.value).toBe(3)
    expect(locks.dayOptions.value).toEqual([
      { day: 1, label: '周一', count: 2 },
      { day: 2, label: '周二', count: 1 },
    ])

    viewMode.value = 'teacher'
    expect(locks.canUseClassScope.value).toBe(false)
    expect(locks.canUseScope('day')).toBe(false)
    expect(locks.canUseScope('selected')).toBe(true)
    expect(() => locks.scope('day', 1)).toThrow('教师/教室视角只能按所选锁定')
  })

  it('所选含未排课节时拒绝，且一次请求都不发', async () => {
    const { locks, setLocks, notify } = setup()
    locks.toggleSelectionMode()
    locks.toggle(1)
    locks.toggle(4)

    expect(() => locks.scope('selected')).toThrow('所选包含未排课节')
    const outcome = await locks.setLocks([1, 4], true)

    expect(outcome.ok).toBe(false)
    expect(setLocks).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('所选包含未排课节，不能锁定')
  })

  it('批量成功发送 lessonIds/locked/expectedVersion，按服务端 updated 报数量并清空选择', async () => {
    const { locks, setLocks, reload, notify } = setup({
      setLocks: async () => ({ requested: 2, updated: 2, locked: true, version: 8 }),
    })
    locks.toggleSelectionMode()
    locks.toggle(2)
    locks.toggle(1)

    const outcome = await locks.setLocks([2, 1, 2], true)

    expect(outcome).toEqual({ ok: true, updated: 2 })
    expect(setLocks).toHaveBeenCalledWith(5, {
      lessonIds: [1, 2],
      locked: true,
      expectedVersion: 7,
    })
    expect(reload).toHaveBeenCalledTimes(1)
    expect(locks.selectedIds.value).toEqual([])
    expect(notify.success).toHaveBeenCalledWith('已锁定 2 节')
  })

  it('updated=0 时提示已是目标状态，不重复计数', async () => {
    const { locks, notify } = setup({
      setLocks: async () => ({ requested: 1, updated: 0, locked: true, version: 8 }),
    })

    await locks.setLocks([1], true)

    expect(notify.success).toHaveBeenCalledWith('这些课节已经是目标状态')
  })

  it('409 显示服务端原因并重载，保留选择供重试；400 混合批次没有部分成功', async () => {
    const conflict = Object.assign(new Error('conflict'), {
      response: { data: { message: '课表已被其他人修改，请刷新后重试' } },
    })
    const { locks, reload, notify, setLocks } = setup({
      setLocks: async () => {
        throw conflict
      },
    })
    locks.toggleSelectionMode()
    locks.toggle(1)
    locks.toggle(2)

    const outcome = await locks.setLocks([1, 2], true)

    expect(outcome.ok).toBe(false)
    expect(outcome.updated).toBe(0)
    expect(notify.error).toHaveBeenCalledWith('课表已被其他人修改，请刷新后重试')
    expect(reload).toHaveBeenCalledTimes(1)
    expect(locks.selectedIds.value).toEqual([1, 2])
    expect(setLocks).toHaveBeenCalledTimes(1)

    // A batch the client cannot pre-validate is refused by the server as a whole;
    // its reason must reach the user and no partial lock may be reported.
    const badRequest = Object.assign(new Error('bad'), {
      response: { data: { message: '非法课节 id: [999]' } },
    })
    setLocks.mockRejectedValueOnce(badRequest)
    const rejected = await locks.setLocks([1, 2], true)
    expect(rejected.ok).toBe(false)
    expect(notify.error).toHaveBeenCalledWith('非法课节 id: [999]')
    expect(setLocks).toHaveBeenCalledTimes(2)
  })

  it('busy 防重复，空范围与不可编辑草稿不发请求', async () => {
    let release: (value: LockResult) => void = () => {}
    const pending = new Promise<LockResult>((resolve) => {
      release = resolve
    })
    const { locks, setLocks, notify } = setup({ setLocks: () => pending })

    const first = locks.setLocks([1], true)
    const second = await locks.setLocks([1], true)
    expect(second.ok).toBe(false)
    expect(setLocks).toHaveBeenCalledTimes(1)
    release({ requested: 1, updated: 1, locked: true, version: 8 })
    expect((await first).ok).toBe(true)
    expect(locks.busy.value).toBe(false)

    await locks.setLocks([], true)
    expect(notify.warning).toHaveBeenCalledWith('请选择要锁定的课节')
    expect(setLocks).toHaveBeenCalledTimes(1)
  })

  it('单节锁定/解锁按 lesson.locked 选择动作', async () => {
    const { locks, setLocks } = setup({
      setLocks: async () => ({ requested: 1, updated: 1, locked: true, version: 8 }),
    })

    await locks.lockSingle({ id: 1 })
    await locks.unlockSingle({ id: 2 })
    await locks.toggleLockSingle(lesson(3, { locked: true }))
    await locks.toggleLockSingle(lesson(1, { locked: false }))

    expect(setLocks.mock.calls.map((call) => [call[1].lessonIds, call[1].locked])).toEqual([
      [[1], true],
      [[2], false],
      [[3], false],
      [[1], true],
    ])
  })

  it('草稿不可编辑时只提示，不请求服务端', async () => {
    const { locks, setLocks, notify } = setup({ editable: false })

    const outcome = await locks.setLocks([1], true)

    expect(outcome.ok).toBe(false)
    expect(setLocks).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('当前草稿不可编辑')
  })
})
