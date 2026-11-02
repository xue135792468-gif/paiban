import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftCompareDialog from './DraftCompareDialog.vue'
import type { Draft, DraftLesson } from './types'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

function draft(id: number, name: string, lessonCount: number): Draft {
  return {
    id,
    semesterId: 3,
    name,
    status: 'DRAFT',
    scoreHard: 0,
    scoreSoft: 0,
    createdAt: '2026-11-02T00:00:00Z',
    lessonCount,
    version: 1,
  }
}

function lesson(overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id: 1,
    assignmentId: 10,
    courseId: 100,
    courseName: '数学',
    teacherId: 5,
    teacherName: '张老师',
    classId: 1,
    className: '高一1班',
    studentCount: 45,
    periodId: 1001,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    locked: false,
    ...overrides,
  }
}

const drafts = [draft(1, '原稿', 2), draft(2, '其他稿', 1), draft(3, '新稿', 2)]
const lessonsById: Record<number, DraftLesson[]> = {
  1: [lesson({ id: 1, assignmentId: 10 }), lesson({ id: 2, assignmentId: 11, teacherId: 6, locked: true, periodId: 1002, dayOfWeek: 2, periodLabel: '周二第1节' })],
  2: [lesson({ id: 3, assignmentId: 12, teacherId: 7 })],
  3: [lesson({ id: 11, assignmentId: 10, periodId: 2001, dayOfWeek: 3, periodLabel: '周三第1节' }), lesson({ id: 12, assignmentId: 11, teacherId: 6, locked: true, periodId: 1002, dayOfWeek: 2, periodLabel: '周二第1节' })],
}

function mountDialog(props: Record<string, unknown>) {
  return mount(DraftCompareDialog, {
    props: { modelValue: true, draftId: 0, semesterId: 3, ...props },
    global: { plugins: [ElementPlus] },
  })
}

describe('DraftCompareDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation(async (url: string) => {
      if (url === '/timetable-drafts') return { data: drafts }
      const id = Number(url.split('/').pop())
      return { data: { lessons: lessonsById[id] ?? [] } }
    })
  })

  it('传入 source/compare 时自动配对 A=原稿 B=新稿，并给出未锁变化摘要', async () => {
    const wrapper = mountDialog({ draftId: 3, sourceDraftId: 1, compareWithDraftId: 3 })
    await flushPromises()

    const fetched = httpMock.get.mock.calls
      .map(([url]) => url)
      .filter((url: string) => url !== '/timetable-drafts')
    expect(fetched).toContain('/timetable-drafts/1')
    expect(fetched).toContain('/timetable-drafts/3')
    expect(fetched).not.toContain('/timetable-drafts/2')

    expect(wrapper.get('[data-testid=compare-diff]').text()).toContain(
      '未锁课节变化 1 节；锁定课节 1 节保持不变。',
    )
    expect(wrapper.text()).toContain('原稿（比较 A）')
    expect(wrapper.text()).toContain('新稿（比较 B）')
  })

  it('两侧表格都有锁标记列', async () => {
    const wrapper = mountDialog({ draftId: 3, sourceDraftId: 1, compareWithDraftId: 3 })
    await flushPromises()

    const lockTags = wrapper.findAll('.el-tag').filter((tag) => tag.text() === '锁')
    expect(lockTags).toHaveLength(2)
  })

  it('锁定课节发生变化时给出红色提示', async () => {
    lessonsById[3] = [
      lesson({ id: 11, assignmentId: 10, periodId: 2001, dayOfWeek: 3 }),
      lesson({ id: 12, assignmentId: 11, teacherId: 6, locked: true, periodId: 9999, dayOfWeek: 4, periodLabel: '周四第1节' }),
    ]
    const wrapper = mountDialog({ draftId: 3, sourceDraftId: 1, compareWithDraftId: 3 })
    await flushPromises()

    expect(wrapper.get('[data-testid=compare-locked-warning]').text()).toContain(
      '锁定课节发生变化',
    )
    lessonsById[3] = [
      lesson({ id: 11, assignmentId: 10, periodId: 2001, dayOfWeek: 3 }),
      lesson({ id: 12, assignmentId: 11, teacherId: 6, locked: true, periodId: 1002, dayOfWeek: 2, periodLabel: '周二第1节' }),
    ]
  })

  it('没有 source/compare 时保持旧默认：A=当前稿，B=第一份其他稿', async () => {
    const wrapper = mountDialog({ draftId: 1 })
    await flushPromises()

    const fetched = httpMock.get.mock.calls
      .map(([url]) => url)
      .filter((url: string) => url !== '/timetable-drafts')
    expect(fetched).toContain('/timetable-drafts/1')
    expect(fetched).toContain('/timetable-drafts/2')
    expect(wrapper.text()).not.toContain('原稿（比较 A）')
    expect(wrapper.text()).toContain('发布以整草稿校验为准；求解得分仅作参考。')
  })
})
