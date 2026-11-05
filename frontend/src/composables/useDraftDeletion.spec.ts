import { describe, expect, it, vi } from 'vitest'
import { deleteDraftWithConfirmation } from './useDraftDeletion'

function setup() {
  const deleteFn = vi.fn().mockResolvedValue({})
  const confirm = vi.fn().mockResolvedValue({})
  const notify = { success: vi.fn(), error: vi.fn() }
  return { client: { delete: deleteFn }, confirm, notify, deleteFn }
}

describe('deleteDraftWithConfirmation', () => {
  it('确认后直接删除草稿，不再询问解锁', async () => {
    const { client, confirm, notify, deleteFn } = setup()

    const outcome = await deleteDraftWithConfirmation(
      { id: 7, name: '草稿' },
      { client, confirm, notify },
    )

    expect(outcome).toBe('deleted')
    const [message, title, options] = confirm.mock.calls[0]
    expect(message).toContain('草稿')
    expect(message).toContain('最近删除')
    expect(title).toBe('删除草稿')
    expect(options).toMatchObject({ confirmButtonText: '确定删除', type: 'warning' })
    expect(deleteFn).toHaveBeenCalledWith('/timetable-drafts/7')
    expect(notify.success).toHaveBeenCalledWith('草稿已删除')
  })

  it('取消确认时不发请求', async () => {
    const { client, confirm, notify, deleteFn } = setup()
    confirm.mockRejectedValue('cancel')

    const outcome = await deleteDraftWithConfirmation(
      { id: 9, name: '草稿' },
      { client, confirm, notify },
    )

    expect(outcome).toBe('cancelled')
    expect(deleteFn).not.toHaveBeenCalled()
    expect(notify.success).not.toHaveBeenCalled()
  })

  it('服务端拒绝时上报其消息并返回 failed', async () => {
    const { client, confirm, notify, deleteFn } = setup()
    deleteFn.mockRejectedValue({ response: { data: { message: '草稿不存在' } } })

    const outcome = await deleteDraftWithConfirmation(
      { id: 10, name: '草稿' },
      { client, confirm, notify },
    )

    expect(outcome).toBe('failed')
    expect(notify.error).toHaveBeenCalledWith('草稿不存在')
  })
})
