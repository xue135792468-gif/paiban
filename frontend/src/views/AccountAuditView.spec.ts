import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AccountAuditView from './AccountAuditView.vue'
import type { AccountAuditEntry, AccountAuditPage } from '@/api/accountAudit'

const mocks = vi.hoisted(() => ({
  listAccountAudits: vi.fn(),
  listAccountAuditActions: vi.fn(),
  listAccountOptions: vi.fn(),
}))

vi.mock('@/api/accountAudit', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/accountAudit')>()),
  listAccountAudits: mocks.listAccountAudits,
  listAccountAuditActions: mocks.listAccountAuditActions,
  listAccountOptions: mocks.listAccountOptions,
}))

function entry(overrides: Partial<AccountAuditEntry> = {}): AccountAuditEntry {
  return {
    id: 1,
    createdAt: '2026-11-11T03:00:00Z',
    actor: 'admin',
    action: 'ACCOUNT_CREATE',
    targetUserId: 2,
    targetUsername: 'zhang',
    targetDisplayName: '张三',
    before: null,
    after: {
      username: 'zhang',
      displayName: '张三',
      enabled: true,
      teacherId: null,
      teacherName: null,
      roleCodes: ['teacher'],
    },
    ...overrides,
  }
}

function pageOf(items: AccountAuditEntry[]): AccountAuditPage {
  return { items, page: 1, size: 20, total: items.length }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

async function mountView() {
  const wrapper = mount(AccountAuditView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

function actionSelectOf(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((select) => select.props('ariaLabel') === '动作类型')!
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.listAccountAuditActions.mockResolvedValue([
    { code: 'ACCOUNT_CREATE', label: '手工创建账号' },
    { code: 'ACCOUNT_IMPORT', label: '批量导入账号' },
  ])
  mocks.listAccountOptions.mockResolvedValue([
    { id: 2, username: 'zhang', displayName: '张三', enabled: true },
  ])
})

describe('AccountAuditView', () => {
  it('迟到的旧响应不会覆盖最新一次筛选结果', async () => {
    const staleFilterRequest = deferred<AccountAuditPage>()
    const latestFilterRequest = deferred<AccountAuditPage>()
    mocks.listAccountAudits
      .mockResolvedValueOnce(pageOf([entry()]))
      .mockReturnValueOnce(staleFilterRequest.promise)
      .mockReturnValueOnce(latestFilterRequest.promise)

    const wrapper = await mountView()
    const actionSelect = actionSelectOf(wrapper)

    // 用户连续改两次筛选：先「手工创建账号」，紧接着改成「批量导入账号」。
    actionSelect.vm.$emit('update:modelValue', 'ACCOUNT_CREATE')
    actionSelect.vm.$emit('change', 'ACCOUNT_CREATE')
    await flushPromises()
    actionSelect.vm.$emit('update:modelValue', 'ACCOUNT_IMPORT')
    actionSelect.vm.$emit('change', 'ACCOUNT_IMPORT')
    await flushPromises()

    latestFilterRequest.resolve(pageOf([entry({ targetDisplayName: '最新结果' })]))
    await flushPromises()
    expect(wrapper.text()).toContain('最新结果')

    // 第一次筛选的响应这时才回来：它属于旧条件，不能把表格改回去。
    staleFilterRequest.resolve(pageOf([entry({ id: 99, targetDisplayName: '过期结果' })]))
    await flushPromises()

    expect(wrapper.text()).toContain('最新结果')
    expect(wrapper.text()).not.toContain('过期结果')
    wrapper.unmount()
  })

  it('换筛选条件回到第一页再查', async () => {
    mocks.listAccountAudits.mockResolvedValue(pageOf([entry()]))
    const wrapper = await mountView()

    const pager = wrapper.findComponent({ name: 'ElPagination' })
    pager.vm.$emit('current-change', 3)
    await flushPromises()
    expect(mocks.listAccountAudits).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }))

    const actionSelect = actionSelectOf(wrapper)
    actionSelect.vm.$emit('update:modelValue', 'ACCOUNT_CREATE')
    actionSelect.vm.$emit('change', 'ACCOUNT_CREATE')
    await flushPromises()

    expect(mocks.listAccountAudits).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, action: 'ACCOUNT_CREATE' }),
    )
    wrapper.unmount()
  })
})
