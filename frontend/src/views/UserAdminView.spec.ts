import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import UserAdminView from './UserAdminView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const users = [
  { id: 1, username: 'admin', displayName: '管理员', enabled: true, roles: ['admin'], permissions: [] },
  { id: 2, username: 'wang', displayName: '王老师', enabled: true, roles: ['teacher'], permissions: [] },
]

beforeEach(() => {
  vi.clearAllMocks()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/users') return Promise.resolve({ data: users })
    if (url === '/roles') {
      return Promise.resolve({ data: [{ id: 1, code: 'admin', name: '教务管理员' }] })
    }
    if (url === '/teachers/options') return Promise.resolve({ data: [] })
    return Promise.resolve({ data: [] })
  })
  httpMock.post.mockResolvedValue({ data: {} })
})

describe('UserAdminView: disable and enable', () => {
  it('disables a user through the dedicated endpoint after confirmation', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.findAll('[data-testid="user-disable"]')[0].trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(httpMock.post).toHaveBeenCalledWith('/users/1/disable')
    wrapper.unmount()
  })

  it('enables a paused user without a confirmation dialog', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/users') {
        return Promise.resolve({ data: [{ ...users[1], enabled: false }] })
      }
      if (url === '/roles') return Promise.resolve({ data: [] })
      if (url === '/teachers/options') return Promise.resolve({ data: [] })
      return Promise.resolve({ data: [] })
    })
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('[data-testid="user-enable"]').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    expect(httpMock.post).toHaveBeenCalledWith('/users/2/enable')
    wrapper.unmount()
  })
})

describe('UserAdminView: 改身份只有三个选项', () => {
  it('编辑弹窗只列出超级管理员、教务管理员与老师', async () => {
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '编辑')!.trigger('click')
    await flushPromises()

    const labels = wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('label'))
    expect(labels).toEqual(['超级管理员', '教务管理员', '老师'])
    wrapper.unmount()
  })

  it('把老师提升为教务管理员时只提交这一个身份', async () => {
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.findAll('button').filter((button) => button.text() === '编辑')[1].trigger('click')
    await flushPromises()

    const identitySelect = wrapper.findComponent({ name: 'ElSelect' })
    identitySelect.vm.$emit('update:modelValue', 'admin')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith('/users/2', {
      displayName: '王老师',
      roleCodes: ['admin'],
      teacherId: undefined,
    })
    wrapper.unmount()
  })
})

/** 多角色账号：后端按字母序返回，编辑弹窗必须按预置身份优先级显示最高身份。 */
const multiRoleUser = {
  id: 3,
  username: 'triple',
  displayName: '三身份账号',
  enabled: true,
  roles: ['admin', 'super_admin', 'teacher'],
  permissions: [],
}

function serveMultiRoleAccount() {
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/users') return Promise.resolve({ data: [multiRoleUser] })
    if (url === '/teachers/options') return Promise.resolve({ data: [] })
    return Promise.resolve({ data: [] })
  })
}

/** 编辑弹窗的作用域：标题唯一，避免与新建弹窗的同名控件串位。 */
function editDialog(wrapper: ReturnType<typeof mount>) {
  const dialog = wrapper
    .findAllComponents({ name: 'ElDialog' })
    .find((component) => component.props('title') === '编辑用户')
  if (!dialog) throw new Error('edit dialog not rendered')
  return dialog
}

async function openEditDialog(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find((button) => button.text() === '编辑')!.trigger('click')
  await flushPromises()
  return editDialog(wrapper)
}

async function saveEditDialog(wrapper: ReturnType<typeof mount>) {
  await editDialog(wrapper).findAll('button').find((button) => button.text() === '保存')!.trigger('click')
  await flushPromises()
}

describe('UserAdminView: 多角色账号编辑不静默降级', () => {
  it('按最高预置身份显示（超级管理员），而不是后端返回顺序里的第一个', async () => {
    serveMultiRoleAccount()
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const dialog = await openEditDialog(wrapper)

    expect(dialog.findAllComponents({ name: 'ElSelect' })[0].props('modelValue')).toBe('super_admin')
    wrapper.unmount()
  })

  it('只改姓名时不上送 roleCodes，三个身份原样保留', async () => {
    serveMultiRoleAccount()
    const wrapper = mount(UserAdminView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const dialog = await openEditDialog(wrapper)
    await dialog.findAllComponents({ name: 'ElInput' })[1].setValue('仅修改姓名')
    await flushPromises()
    await saveEditDialog(wrapper)

    const payload = httpMock.put.mock.calls[0][1] as Record<string, unknown>
    expect(payload.displayName).toBe('仅修改姓名')
    expect(payload).not.toHaveProperty('roleCodes')
    wrapper.unmount()
  })
})
