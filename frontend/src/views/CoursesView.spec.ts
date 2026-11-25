import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import CoursesView from './CoursesView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const courses = [
  {
    id: 9,
    name: '体育',
    subject: '综合',
    allowConsecutive: false,
    color: '#4F6EF7',
    active: true,
    weeklyHours: null,
  },
  {
    id: 10,
    name: '数学',
    subject: '理科',
    allowConsecutive: true,
    color: '#10B981',
    active: true,
    weeklyHours: 5,
  },
]

const groups = [
  {
    id: 21,
    type: 'COURSE',
    name: '技能课',
    semesterId: null,
    members: [
      { id: 1, kind: 'COURSE', objectId: 9, name: '体育', dayOfWeek: null, periodNo: null },
    ],
  },
]

function installUser() {
  localStorage.setItem('paiban_user', JSON.stringify({
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    roles: ['admin'],
    permissions: ['foundation:manage', 'constraint:manage'],
  }))
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(CoursesView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

function groupFilter(wrapper: VueWrapper) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === 'course-group-filter')
  if (!found) throw new Error('group filter not found')
  return found
}

beforeEach(() => {
  localStorage.clear()
  installUser()
  httpMock.get.mockReset()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/courses') return Promise.resolve({ data: courses })
    if (url === '/rule-groups') return Promise.resolve({ data: groups })
    return Promise.resolve({ data: [] })
  })
})

describe('CoursesView: course group suffix and filter', () => {
  it('shows the group suffix after the course name', async () => {
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="course-group-suffix-9"]').text()).toBe('· 技能课')
    expect(wrapper.find('[data-testid="course-group-suffix-10"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('filters the list down to the members of the picked group', async () => {
    const wrapper = await mountView()
    groupFilter(wrapper).vm.$emit('update:modelValue', 21)
    await flushPromises()

    expect(wrapper.text()).toContain('体育')
    expect(wrapper.text()).not.toContain('数学')
    wrapper.unmount()
  })

  it('keeps the whole list when 全部分组 is picked', async () => {
    const wrapper = await mountView()
    groupFilter(wrapper).vm.$emit('update:modelValue', 21)
    await flushPromises()
    groupFilter(wrapper).vm.$emit('update:modelValue', 'all')
    await flushPromises()

    expect(wrapper.text()).toContain('体育')
    expect(wrapper.text()).toContain('数学')
    wrapper.unmount()
  })
})

it('shows and clears the course default weekly hours through the edit form', async () => {
  const wrapper = await mountView()
  httpMock.put.mockResolvedValue({ data: {} })
  const math = wrapper.findAll('tr').find((row) => row.text().includes('数学'))
  expect(math?.text()).toContain('5')
  expect(wrapper.text()).toContain('周课时数')
  const edit = math?.findAll('button').find((button) => button.text() === '编辑')
  if (!edit) throw new Error('找不到数学课程的编辑按钮')
  await edit.trigger('click')
  await flushPromises()
  const input = document.body.querySelector<HTMLInputElement>('[role="dialog"] input[inputmode="numeric"]')
  const dialog = input?.closest('[role="dialog"]')
  expect(input?.value).toBe('5')
  if (!input) throw new Error('周课时数输入框未渲染')
  input.value = ''
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  const save = [...(dialog?.querySelectorAll('button') ?? [])].find((button) => button.textContent?.trim() === '保存')
  save?.click()
  await flushPromises()
  expect(httpMock.put).toHaveBeenCalledWith('/courses/10', expect.objectContaining({ weeklyHours: null }))
  wrapper.unmount()
})
