import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useDraftDrop } from './useDraftDrop'
import type { useDraftAdjust, ChangeOutcome } from './useDraftAdjust'
import type { useCyclePlans } from './useCyclePlans'
import type { DraftDetail, DraftLesson, DraftViolation } from '@/components/draft/types'

function lesson(id: number, periodId: number | null, overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId: 9,
    teacherName: `教师${id}`,
    classId: 1,
    className: '高一1班',
    studentCount: 40,
    periodId,
    dayOfWeek: 1,
    periodNo: id,
    roomId: 5,
    locked: false,
    ...overrides,
  }
}

function detail(lessons: DraftLesson[]): DraftDetail {
  return {
    draft: {
      id: 1,
      semesterId: 1,
      name: '草稿',
      status: 'DRAFT',
      scoreHard: 0,
      scoreSoft: 0,
      createdAt: '2026-01-01T00:00:00Z',
      lessonCount: lessons.length,
      version: 4,
    },
    lessons,
    periods: [],
    rooms: [],
    rulesFingerprint: 'fp-1',
  }
}

function saved(): ChangeOutcome {
  return { saved: true, addedConflicts: [], remainingConflicts: [], conflicts: [] }
}

function refused(added: DraftViolation[], conflicts = added.map((item) => ({
  type: item.code,
  message: item.message,
  lessonId: item.lessonId ?? 0,
}))): ChangeOutcome {
  return { saved: false, addedConflicts: added, remainingConflicts: [], conflicts }
}

const blocked: DraftViolation = {
  key: 'k',
  code: 'CLASS_CONFLICT',
  message: '本班已有课',
  lessonId: 2,
  severity: 1,
}

function setup(options: {
  source: DraftLesson
  target: number
  cell: DraftLesson[]
  /** Lessons the current view actually shows in the target cell; defaults to `cell`. */
  visible?: number[]
  viewMode?: 'class' | 'teacher' | 'room'
  moveTo?: ReturnType<typeof vi.fn>
  swapLessons?: ReturnType<typeof vi.fn>
  search?: ReturnType<typeof vi.fn>
}) {
  const adjust = {
    moveTo: options.moveTo ?? vi.fn(async () => saved()),
    swapLessons: options.swapLessons ?? vi.fn(async () => saved()),
  }
  const cyclePlans = { search: options.search ?? vi.fn() }
  const reload = vi.fn(async () => undefined)
  const notify = { warning: vi.fn(), error: vi.fn() }
  const showConflicts = vi.fn()
  const detailRef = ref(detail([options.source, ...options.cell]))
  const draftEditable = ref(true)
  const visibleIds = options.visible ?? options.cell.map((item) => item.id)
  const { drop } = useDraftDrop({
    detail: detailRef,
    draftEditable,
    adjust: adjust as unknown as ReturnType<typeof useDraftAdjust>,
    cyclePlans: cyclePlans as unknown as ReturnType<typeof useCyclePlans>,
    reload,
    notify,
    showConflicts,
  })
  const dropVisible = (source: DraftLesson, periodId: number) => drop(source, periodId, visibleIds)
  return { drop: dropVisible, adjust, cyclePlans, reload, notify, showConflicts }
}

describe('useDraftDrop', () => {
  it('同班有课格直接交换，不打开候选抽屉', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const { drop, adjust, cyclePlans, reload, notify } = setup({
      source,
      target: 200,
      cell: [target],
    })

    await drop(source, 200)

    expect(adjust.swapLessons).toHaveBeenCalledWith(source, target, 4, 'fp-1')
    expect(adjust.moveTo).not.toHaveBeenCalled()
    expect(cyclePlans.search).not.toHaveBeenCalled()
    expect(reload).toHaveBeenCalled()
    expect(notify.warning).not.toHaveBeenCalled()
  })

  it('空格直接移动', async () => {
    const source = lesson(1, 100)
    const { drop, adjust, reload } = setup({ source, target: 300, cell: [] })

    await drop(source, 300)

    expect(adjust.moveTo).toHaveBeenCalledWith(source, 300, 4, 'fp-1')
    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(reload).toHaveBeenCalled()
  })

  it('拖回自身不产生任何调用', async () => {
    const source = lesson(1, 100)
    const { drop, adjust, reload } = setup({ source, target: 100, cell: [source] })

    await drop(source, 100)

    expect(adjust.moveTo).not.toHaveBeenCalled()
    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()
  })

  it('班级视角下，目标时间有其他班课程但当前视图看不到时按普通移动处理', async () => {
    const source = lesson(1, 100)
    const foreign = lesson(2, 200, { classId: 2, className: '高一2班' })
    const { drop, adjust, notify } = setup({
      source,
      target: 200,
      cell: [foreign],
      visible: [],
    })

    await drop(source, 200)

    expect(adjust.moveTo).toHaveBeenCalledWith(source, 200, 4, 'fp-1')
    expect(notify.warning).not.toHaveBeenCalled()
  })

  it('同班目标与其他班课程同一时间时，仍然直接交换', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const foreign = lesson(3, 200, { classId: 2, className: '高一2班' })
    const { drop, adjust, notify } = setup({ source, target: 200, cell: [target, foreign] })

    await drop(source, 200)

    expect(adjust.swapLessons).toHaveBeenCalledWith(source, target, 4, 'fp-1')
    expect(adjust.moveTo).not.toHaveBeenCalled()
    expect(notify.warning).not.toHaveBeenCalled()
  })

  it('教师视角下，落点是别班课程时提示走换课入口', async () => {
    const source = lesson(1, 100)
    const foreign = lesson(2, 200, { classId: 2, className: '高一2班' })
    const { drop, adjust, notify } = setup({
      source,
      target: 200,
      cell: [foreign],
      viewMode: 'teacher',
    })

    await drop(source, 200)

    expect(adjust.moveTo).not.toHaveBeenCalled()
    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('跨班落点只能通过换课入口调整教师')
  })

  it('同格多课不猜目标，提示先修复', async () => {
    const source = lesson(1, 100)
    const first = lesson(2, 200)
    const second = lesson(3, 200)
    const { drop, adjust, notify } = setup({ source, target: 200, cell: [first, second] })

    await drop(source, 200)

    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('该格有多节课，请先修复后再拖拽')
  })

  it('来源锁定时不进入替代搜索', async () => {
    const source = lesson(1, 100, { locked: true })
    const target = lesson(2, 200)
    const { drop, adjust, cyclePlans, notify } = setup({
      source,
      target: 200,
      cell: [target],
    })

    await drop(source, 200)

    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(cyclePlans.search).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('该课节已锁定，请先解锁后再移动')
  })

  it('落点锁定时不进入替代搜索', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200, { locked: true })
    const { drop, adjust, cyclePlans, notify } = setup({
      source,
      target: 200,
      cell: [target],
    })

    await drop(source, 200)

    expect(adjust.swapLessons).not.toHaveBeenCalled()
    expect(cyclePlans.search).not.toHaveBeenCalled()
    expect(notify.warning).toHaveBeenCalledWith('落点课节已锁定，不能换位')
  })

  it('直换失败且有新增冲突时自动搜索方案', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const search = vi.fn(async () => ({
      status: 'FOUND',
      message: '找到 1 个方案',
      version: 4,
      fingerprint: 'fp-1',
      plans: [{ token: 't1' }],
    }))
    const { drop, cyclePlans } = setup({
      source,
      target: 200,
      cell: [target],
      swapLessons: vi.fn(async () => refused([blocked])),
      search,
    })

    await drop(source, 200)

    expect(cyclePlans.search).toHaveBeenCalledWith(1, 200, 2, 4, 'fp-1')
  })

  it('搜索结果为 NONE 时给出“当前范围没有方案”的提示', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const { drop, notify } = setup({
      source,
      target: 200,
      cell: [target],
      swapLessons: vi.fn(async () => refused([blocked])),
      search: vi.fn(async () => ({
        status: 'NONE',
        message: '当前范围和上限内没有可用方案',
        version: 4,
        fingerprint: 'fp-1',
        plans: [],
      })),
    })

    await drop(source, 200)

    expect(notify.warning).toHaveBeenCalledWith('当前范围和上限内没有可用方案')
  })

  it('搜索预算耗尽且无方案时区分“搜索未完成”', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const { drop, notify } = setup({
      source,
      target: 200,
      cell: [target],
      swapLessons: vi.fn(async () => refused([blocked])),
      search: vi.fn(async () => ({
        status: 'BUDGET_EXHAUSTED',
        message: '搜索预算耗尽，尚未找到方案；这不代表一定无解',
        version: 4,
        fingerprint: 'fp-1',
        plans: [],
      })),
    })

    await drop(source, 200)

    expect(notify.warning).toHaveBeenCalledWith('搜索未完成，尚未找到方案；这不代表一定无解')
  })

  it('直换因网络/过期失败且没有新增冲突时刷新对齐状态', async () => {
    const source = lesson(1, 100)
    const target = lesson(2, 200)
    const { drop, cyclePlans, reload, showConflicts } = setup({
      source,
      target: 200,
      cell: [target],
      swapLessons: vi.fn(async () => ({
        saved: false,
        addedConflicts: [],
        remainingConflicts: [],
        conflicts: [],
        error: '课表已被其他人修改',
      })),
    })

    await drop(source, 200)

    expect(cyclePlans.search).not.toHaveBeenCalled()
    expect(reload).toHaveBeenCalled()
    expect(showConflicts).not.toHaveBeenCalled()
  })
})
