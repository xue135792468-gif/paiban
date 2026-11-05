import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RecentDeletionsView from './RecentDeletionsView.vue'
import type { RecentDeletion } from '@/api/lifecycle'

const mocks = vi.hoisted(() => ({
  listRecentDeletions: vi.fn(),
  restoreDeletion: vi.fn(),
}))

vi.mock('@/api/lifecycle', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/lifecycle')>()
  return {
    ...actual,
    listRecentDeletions: mocks.listRecentDeletions,
    restoreDeletion: mocks.restoreDeletion,
  }
})

function row(overrides: Partial<RecentDeletion> = {}): RecentDeletion {
  return {
    id: 1,
    batchId: 'batch-1',
    entityType: 'COURSE',
    entityId: 5,
    entityLabel: '语文',
    primary: true,
    deletedBy: 'admin',
    deletedAt: '2026-11-01T02:00:00Z',
    expiresAt: '2026-11-08T02:00:00Z',
    restoredAt: null,
    restoredBy: null,
    status: 'PENDING',
    daysLeft: 4,
    ...overrides,
  }
}

describe('RecentDeletionsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('groups a batch and refreshes the list after restoring it', async () => {
    mocks.listRecentDeletions.mockResolvedValue([
      row(),
      row({
        id: 2,
        entityType: 'ASSIGNMENT',
        entityId: 9,
        entityLabel: '初一1班 / 语文',
        primary: false,
      }),
    ])
    mocks.restoreDeletion.mockResolvedValue({ batchId: 'batch-1', restoredCount: 2 })

    const wrapper = mount(RecentDeletionsView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('语文')
    expect(wrapper.text()).toContain('共 2 项')
    expect(wrapper.text()).toContain('剩余 4 天')

    await wrapper.get('[data-testid="restore-batch"]').trigger('click')
    await flushPromises()

    expect(mocks.restoreDeletion).toHaveBeenCalledWith('batch-1')
    expect(mocks.listRecentDeletions).toHaveBeenCalledTimes(2)
  })

  it('shows restored and expired batches without a restore action', async () => {
    mocks.listRecentDeletions.mockResolvedValue([
      row({ status: 'RESTORED', restoredAt: '2026-11-02T02:00:00Z' }),
      row({ id: 3, batchId: 'batch-2', status: 'EXPIRED', daysLeft: 0 }),
    ])

    const wrapper = mount(RecentDeletionsView, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('已恢复')
    expect(wrapper.text()).toContain('已过期')
    expect(wrapper.find('[data-testid="restore-batch"]').exists()).toBe(false)
  })
})
