import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RoomFillDialog from './RoomFillDialog.vue'
import type { RoomFillPlan } from './roomFill'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

function plan(overrides: Partial<RoomFillPlan> = {}): RoomFillPlan {
  return {
    applied: false,
    applicable: true,
    createdRooms: 2,
    reusedRooms: 0,
    assignedLessons: 4,
    classes: [
      {
        classId: 1,
        className: '八（1）班',
        studentCount: 45,
        requiredCapacity: 45,
        lessonCount: 2,
        roomName: '2025-2026学年第一学期-八（1）班-固定教室',
        roomId: null,
        roomCapacity: 45,
        action: 'CREATE',
      },
      {
        classId: 2,
        className: '八（2）班',
        studentCount: 38,
        requiredCapacity: 38,
        lessonCount: 2,
        roomName: '2025-2026学年第一学期-八（2）班-固定教室',
        roomId: 9,
        roomCapacity: 38,
        action: 'REUSE',
      },
    ],
    manualItems: [],
    blockers: [],
    ...overrides,
  }
}

function mountDialog() {
  return mount(RoomFillDialog, {
    props: { modelValue: true, draftId: 7 },
    global: { plugins: [ElementPlus] },
  })
}

describe('RoomFillDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({ data: plan() })
    httpMock.post.mockResolvedValue({ data: plan({ applied: true, assignedLessons: 4 }) })
  })

  it('打开时读取计划并列出每个行政班的固定教室、方式与人数依据', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    expect(httpMock.get).toHaveBeenCalledWith('/timetable-drafts/7/room-fill/plan')
    const rows = wrapper.findAll('[data-testid=room-fill-classes] tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('八（1）班')
    expect(rows[0].text()).toContain('2025-2026学年第一学期-八（1）班-固定教室')
    expect(rows[0].text()).toContain('新建')
    expect(rows[0].text()).toContain('45 人')
    expect(rows[1].text()).toContain('复用')
    expect(wrapper.get('[data-testid=room-fill-confirm]').attributes('disabled')).toBeUndefined()
  })

  it('有人工处理项时展示原因并禁用确认', async () => {
    httpMock.get.mockResolvedValue({
      data: plan({
        applicable: false,
        classes: [plan().classes[1]],
        manualItems: [
          {
            classId: 1,
            className: '八（1）班',
            code: 'SPECIAL_ROOM_REQUIRED',
            message: '「物理实验」需要专用教室，固定普通教室不能代替，请手工分配',
          },
        ],
      }),
    })
    const wrapper = mountDialog()
    await flushPromises()

    expect(wrapper.get('[data-testid=room-fill-manual]').text()).toContain('物理实验')
    const confirm = wrapper.get('[data-testid=room-fill-confirm]')
    expect(confirm.attributes('disabled')).toBeDefined()
    await confirm.trigger('click')
    expect(httpMock.post).not.toHaveBeenCalled()
  })

  it('补完后仍会阻止发布的事项照实展示，不声称全部解决', async () => {
    httpMock.get.mockResolvedValue({
      data: plan({
        blockers: [
          { code: 'HARD_CONFLICTS', message: '课表存在 2 处排课冲突', count: 2, action: 'NONE' },
        ],
      }),
    })
    const wrapper = mountDialog()
    await flushPromises()

    expect(wrapper.get('[data-testid=room-fill-remaining]').text()).toContain('2 处排课冲突')
  })

  it('确认后调用应用接口并通知页面刷新', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.get('[data-testid=room-fill-confirm]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/timetable-drafts/7/room-fill')
    expect(wrapper.emitted('applied')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })

  it('服务端拒绝时展示它返回的人工处理项', async () => {
    httpMock.post.mockRejectedValue({
      response: {
        data: plan({
          applied: false,
          applicable: false,
          classes: [],
          manualItems: [
            {
              classId: null,
              className: null,
              code: 'SEMESTER_ALREADY_PUBLISHED',
              message: '该学期课表已发布，基线只读',
            },
          ],
        }),
      },
    })
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.get('[data-testid=room-fill-confirm]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('applied')).toBeUndefined()
    expect(wrapper.get('[data-testid=room-fill-manual]').text()).toContain('基线只读')
  })
})
