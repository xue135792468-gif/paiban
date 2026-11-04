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
