import { describe, expect, it, vi } from 'vitest'
import { withdrawDraftWithConfirmation } from './useDraftWithdraw'

function setup(preview: Record<string, unknown> | Error = {
  published: true,
  futureSubstitutions: 2,
  allSubstitutions: 3,
}) {
  const get = vi.fn()
  if (preview instanceof Error) {
    get.mockRejectedValue(preview)
  } else {
    get.mockResolvedValue({ data: preview })
  }
  const post = vi.fn().mockResolvedValue({
    data: { draftId: 7, publishId: 3, cancelledSubstitutions: 2, removedEntries: 12 },
  })
  const confirm = vi.fn().mockResolvedValue({})
  const notify = { success: vi.fn(), error: vi.fn() }
  return { client: { get, post }, confirm, notify, get, post }
}

describe('withdrawDraftWithConfirmation', () => {
  it('确认文案点名未来调代数量，并调用 preview + withdraw', async () => {
    const { client, confirm, notify, get, post } = setup()

    const outcome = await withdrawDraftWithConfirmation(
      { id: 7, semesterId: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('withdrawn')
    expect(get).toHaveBeenCalledWith('/timetable/withdraw-preview', {
      params: { semesterId: 1 },
    })
    const [message, title, options] = confirm.mock.calls[0]
    expect(message).toContain('有 2 条未来日期的调代会失效')
    expect(message).toContain('记录保留在历史')
    expect(message).toContain('可编辑并重新发布')
    expect(title).toBe('撤回课表')
    expect(options).toMatchObject({ confirmButtonText: '撤回为草稿', type: 'warning' })
    expect(post).toHaveBeenCalledWith('/timetable-drafts/7/withdraw')
    expect(notify.success).toHaveBeenCalledWith('已撤回为草稿，2 条未来调代已失效')
  })

  it('没有未来调代时仍允许撤回', async () => {
    const { client, confirm, notify, post } = setup({
      published: true,
      futureSubstitutions: 0,
      allSubstitutions: 0,
    })

    const outcome = await withdrawDraftWithConfirmation(
      { id: 8, semesterId: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('withdrawn')
    expect(confirm.mock.calls[0][0]).toContain('当前没有未来日期的调代会失效')
    expect(post).toHaveBeenCalledWith('/timetable-drafts/8/withdraw')
  })

  it('取消确认时不发撤回请求', async () => {
    const { client, confirm, notify, post } = setup()
    confirm.mockRejectedValue('cancel')

    const outcome = await withdrawDraftWithConfirmation(
      { id: 9, semesterId: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('cancelled')
    expect(post).not.toHaveBeenCalled()
  })

  it('没有生效发布时提示并不弹确认', async () => {
    const { client, confirm, notify, post } = setup({
      published: false,
      futureSubstitutions: 0,
      allSubstitutions: 4,
    })

    const outcome = await withdrawDraftWithConfirmation(
      { id: 10, semesterId: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('failed')
    expect(confirm).not.toHaveBeenCalled()
    expect(post).not.toHaveBeenCalled()
    expect(notify.error).toHaveBeenCalledWith('当前学期没有已发布课表可撤回')
  })

  it('撤回失败时显示服务端原因', async () => {
    const { client, confirm, notify, post } = setup()
    post.mockRejectedValue({ response: { data: { message: '该课表没有可撤回的发布记录' } } })

    const outcome = await withdrawDraftWithConfirmation(
      { id: 11, semesterId: 1 },
      { client, confirm, notify },
    )

    expect(outcome).toBe('failed')
    expect(notify.error).toHaveBeenCalledWith('该课表没有可撤回的发布记录')
  })
})
