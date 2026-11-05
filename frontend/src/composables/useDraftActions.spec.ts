import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const { post, confirm } = vi.hoisted(() => ({
  post: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/http', () => ({
  http: { post: (...args: unknown[]) => post(...args), put: vi.fn() },
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm, prompt: vi.fn() },
}))

import { useDraftActions } from './useDraftActions'

function detail() {
  return ref({
    draft: { id: 1, name: '高一课表', status: 'DRAFT', semesterId: 2 },
    lessons: [],
  } as never)
}

function actions() {
  return useDraftActions({ draftId: 1, detail: detail(), reload: vi.fn() })
}

describe('useDraftActions 发布后的调代提示', () => {
  beforeEach(() => {
    post.mockReset()
    confirm.mockReset().mockResolvedValue({})
  })

  it('发布响应带回撤回遗留的调代数量时暴露给页面', async () => {
    post.mockResolvedValue({ data: { entryCount: 12, cancelledSubstitutions: 3 } })
    const draftActions = actions()

    await draftActions.publish()

    expect(post).toHaveBeenCalledWith('/timetable-drafts/1/publish')
    expect(draftActions.cancelledSubstitutions.value).toBe(3)
  })

  it('没有遗留调代时数量为 0', async () => {
    post.mockResolvedValue({ data: { entryCount: 12, cancelledSubstitutions: 0 } })
    const draftActions = actions()

    await draftActions.publish()

    expect(draftActions.cancelledSubstitutions.value).toBe(0)
  })

  it('关闭提示后数量归零', async () => {
    post.mockResolvedValue({ data: { entryCount: 12, cancelledSubstitutions: 1 } })
    const draftActions = actions()
    await draftActions.publish()

    draftActions.dismissCancelledSubstitutions()

    expect(draftActions.cancelledSubstitutions.value).toBe(0)
  })

  it('取消发布确认时不调用接口也不改动数量', async () => {
    confirm.mockRejectedValue('cancel')
    const draftActions = actions()

    await draftActions.publish()

    expect(post).not.toHaveBeenCalled()
    expect(draftActions.cancelledSubstitutions.value).toBe(0)
  })
})
