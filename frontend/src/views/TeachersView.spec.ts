import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import TeachersView from './TeachersView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const teachers = [
  {
    id: 7,
    name: '王老师',
    employeeNo: 'T1',
    active: true,
    subject: '数学',
    dingtalkUserId: 'dd-wang-7',
    bindingStatus: 'dingtalk',
  },
  {
    id: 8,
    name: '李老师',
    employeeNo: 'T2',
    active: true,
    subject: '语文',
    bindingStatus: 'none',
  },
]

const groups = [
  {
    id: 12,
    type: 'TEACHER',
    name: '教导主任',
    semesterId: null,
    members: [
      { id: 1, kind: 'TEACHER', objectId: 7, name: '王老师', dayOfWeek: null, periodNo: null },
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
  const wrapper = mount(TeachersView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

function groupFilter(wrapper: VueWrapper) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === 'teacher-group-filter')
  if (!found) throw new Error('group filter not found')
  return found
}

beforeEach(() => {
  localStorage.clear()
  installUser()
  httpMock.get.mockReset()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/teachers') return Promise.resolve({ data: teachers })
    if (url === '/rule-groups') return Promise.resolve({ data: groups })
    return Promise.resolve({ data: [] })
  })
})

describe('TeachersView: teacher group suffix and filter', () => {
  it('shows the group suffix after the teacher name', async () => {
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="teacher-group-suffix-7"]').text()).toBe('· 教导主任')
    expect(wrapper.find('[data-testid="teacher-group-suffix-8"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('filters the list down to the members of the picked group', async () => {
    const wrapper = await mountView()
    groupFilter(wrapper).vm.$emit('update:modelValue', 12)
    await flushPromises()

    expect(wrapper.text()).toContain('王老师')
    expect(wrapper.text()).not.toContain('李老师')
    wrapper.unmount()
  })
})

describe('TeachersView: 钉钉 UserID 仅超级管理员可改（integration:manage）', () => {
  it('教务管理员（无 integration:manage）编辑教师时钉钉 UserID 只读可见，修改姓名提交时省略 dingtalkUserId', async () => {
    let capturedPutPayload: Record<string, unknown> | undefined
    httpMock.put.mockImplementation((_url: string, payload: Record<string, unknown>) => {
      capturedPutPayload = payload
      return Promise.resolve({ data: {} })
    })

    const wrapper = await mountView()
    await wrapper.findAll('button').find((btn) => btn.text() === '编辑')!.trigger('click')
    await flushPromises()

    const uidInput = wrapper.find('[data-testid="teacher-dingtalk-userid"]')
    expect(uidInput.exists()).toBe(true)
    expect((uidInput.element as HTMLInputElement).value).toBe('dd-wang-7')
    expect((uidInput.element as HTMLInputElement).disabled).toBe(true)

    const nameInput = wrapper.find('input[placeholder="请输入教师姓名"]')
    await nameInput.setValue('王老师（教务改名）')
    await flushPromises()

    await wrapper.findAll('button').find((btn) => btn.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(capturedPutPayload).toBeDefined()
    expect(capturedPutPayload?.name).toBe('王老师（教务改名）')
    expect(capturedPutPayload).not.toHaveProperty('dingtalkUserId')
    wrapper.unmount()
  })

  it('超级管理员（有 integration:manage）可编辑并提交钉钉 UserID', async () => {
    localStorage.setItem(
      'paiban_user',
      JSON.stringify({
        id: 1,
        username: 'admin',
        displayName: '超管',
        enabled: true,
        roles: ['super_admin'],
        permissions: ['foundation:manage', 'constraint:manage', 'integration:manage'],
      }),
    )
    let capturedPutPayload: Record<string, unknown> | undefined
    httpMock.put.mockImplementation((_url: string, payload: Record<string, unknown>) => {
      capturedPutPayload = payload
      return Promise.resolve({ data: {} })
    })

    const wrapper = await mountView()
    await wrapper.findAll('button').find((btn) => btn.text() === '编辑')!.trigger('click')
    await flushPromises()

    const uidInput = wrapper.find('[data-testid="teacher-dingtalk-userid"]')
    expect((uidInput.element as HTMLInputElement).disabled).toBe(false)
    await uidInput.setValue('dd-super-updated')
    await flushPromises()

    await wrapper.findAll('button').find((btn) => btn.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(capturedPutPayload?.dingtalkUserId).toBe('dd-super-updated')
    wrapper.unmount()
  })
})
