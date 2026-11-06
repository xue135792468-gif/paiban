import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import ScheduleEditorView from './ScheduleEditorView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const periods = [1, 5].flatMap((periodNo) => [1, 2, 3, 4, 5].map((dayOfWeek) => ({
  id: periodNo * 10 + dayOfWeek,
  dayOfWeek,
  periodNo,
  label: `第${periodNo}节`,
  startTime: periodNo === 1 ? '08:00' : '14:00',
  endTime: periodNo === 1 ? '08:45' : '14:45',
})))

const morning = {
  id: 5,
  type: 'TIME',
  name: '上午',
  semesterId: 1,
  autoFilled: true,
  members: [1, 2, 3, 4, 5].map((dayOfWeek) => ({
    id: dayOfWeek,
    kind: 'TIME',
    objectId: 0,
    name: `周一第1节`,
    dayOfWeek,
    periodNo: 1,
  })),
}

const afternoon = {
  id: 6,
  type: 'TIME',
  name: '下午',
  semesterId: 1,
  autoFilled: true,
  members: [1, 2, 3, 4, 5].map((dayOfWeek) => ({
    id: 10 + dayOfWeek,
    kind: 'TIME',
    objectId: 0,
    name: `周一第5节`,
    dayOfWeek,
    periodNo: 5,
  })),
}

beforeEach(() => {
  httpMock.get.mockReset()
  httpMock.post.mockReset()
  httpMock.put.mockReset()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/semesters/1') {
      return Promise.resolve({ data: { id: 1, name: '2025 秋' } })
    }
    if (url === '/semesters/1/periods') {
      return Promise.resolve({ data: periods })
    }
    if (url === '/rule-groups') {
      return Promise.resolve({ data: [morning, afternoon] })
    }
    return Promise.resolve({ data: [] })
  })
})

async function mountView(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/data/semesters/:id/schedule', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/data/semesters/1/schedule')
  await router.isReady()
  const wrapper = mount(ScheduleEditorView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

function groupFilter(wrapper: VueWrapper) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === 'schedule-group-filter')
  if (!found) throw new Error('schedule group filter not found')
  return found
}

describe('ScheduleEditorView: time group suffix and filter', () => {
  it('shows which time group each period belongs to', async () => {
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="schedule-time-group-1"]').text()).toBe('上午')
    expect(wrapper.get('[data-testid="schedule-time-group-5"]').text()).toBe('下午')
    wrapper.unmount()
  })

  it('filters the rows down to the members of the picked time group', async () => {
    const wrapper = await mountView()
    groupFilter(wrapper).vm.$emit('update:modelValue', '上午')
    await flushPromises()

    expect(wrapper.find('[data-testid="schedule-time-group-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="schedule-time-group-5"]').exists()).toBe(false)

    groupFilter(wrapper).vm.$emit('update:modelValue', '')
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-time-group-5"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows a dash when a period belongs to no group', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/1') return Promise.resolve({ data: { id: 1, name: '2025 秋' } })
      if (url === '/semesters/1/periods') return Promise.resolve({ data: periods })
      if (url === '/rule-groups') return Promise.resolve({ data: [morning] })
      return Promise.resolve({ data: [] })
    })
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="schedule-time-group-5"]').text()).toBe('—')
    wrapper.unmount()
  })
})
