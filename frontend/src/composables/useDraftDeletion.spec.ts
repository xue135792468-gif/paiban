import { describe, expect, it, vi } from 'vitest'
import { deleteDraftWithConfirmation } from './useDraftDeletion'

function setup() {
  const deleteFn = vi.fn().mockResolvedValue({})
  const confirm = vi.fn().mockResolvedValue({})
  const notify = { success: vi.fn(), error: vi.fn() }
  return { client: { delete: deleteFn }, confirm, notify, deleteFn }
}

describe('deleteDraftWithConfirmation', () => {
  it('锁定草稿要求确认解锁数量，并在请求里带上该数量', async () => {
    const { client, confirm, notify, deleteFn } = setup()

    const outcome = await deleteDraftWithConfirmation(
      { id: 7, name: '锁定草稿', lockedCount: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('deleted')
    const [message, title, options] = confirm.mock.calls[0]
    expect(message).toContain('含 1 节锁定课节')
    expect(message).toContain('一并移除')
    expect(message).toContain('最近删除')
    expect(title).toBe('删除草稿')
    expect(options).toMatchObject({ confirmButtonText: '解锁并删除', type: 'warning' })
    expect(deleteFn).toHaveBeenCalledWith('/timetable-drafts/7', {
      params: { unlockLocked: true, expectedLockedCount: 1 },
    })
    expect(notify.success).toHaveBeenCalledWith('草稿已删除')
  })

  it('无锁草稿沿用普通删除确认，并显式上报 0 个锁定', async () => {
    const { client, confirm, notify, deleteFn } = setup()

    const outcome = await deleteDraftWithConfirmation(
      { id: 8, name: '普通草稿', lockedCount: 0 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('deleted')
    const [message, , options] = confirm.mock.calls[0]
    expect(message).toContain('普通草稿')
    expect(message).toContain('最近删除')
    expect(options).toMatchObject({ confirmButtonText: '确定删除', type: 'warning' })
    expect(deleteFn).toHaveBeenCalledWith('/timetable-drafts/8', {
      params: { unlockLocked: false, expectedLockedCount: 0 },
    })
  })

  it('取消确认时不发请求', async () => {
    const { client, confirm, notify, deleteFn } = setup()
    confirm.mockRejectedValue('cancel')

    const outcome = await deleteDraftWithConfirmation(
      { id: 9, name: '草稿', lockedCount: 0 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('cancelled')
    expect(deleteFn).not.toHaveBeenCalled()
    expect(notify.success).not.toHaveBeenCalled()
  })

  it('服务端拒绝时上报其消息并返回 failed', async () => {
    const { client, confirm, notify, deleteFn } = setup()
    deleteFn.mockRejectedValue({ response: { data: { message: '锁定状态已变化，请刷新后重试' } } })

    const outcome = await deleteDraftWithConfirmation(
      { id: 10, name: '草稿', lockedCount: 2 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('failed')
    expect(notify.error).toHaveBeenCalledWith('锁定状态已变化，请刷新后重试')
  })
})
