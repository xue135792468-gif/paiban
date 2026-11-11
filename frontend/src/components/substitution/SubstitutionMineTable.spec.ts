import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import SubstitutionMineTable from './SubstitutionMineTable.vue'
import type { RequestRow } from './types'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

function row(overrides: Partial<RequestRow>): RequestRow {
  return {
    id: 1,
    entryId: 11,
    className: '高一1班',
    courseName: '数学',
    originalTeacherName: '张老师',
    effectiveDate: '2026-11-16',
    periodLabel: '周一第 1 节',
    type: 'COVER',
    status: 'SUBMITTED',
    createdAt: '2026-11-11T09:00:00Z',
    ...overrides,
  }
}

async function mountTable(requests: RequestRow[]): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/substitution')
  await router.isReady()
  const wrapper = mount(SubstitutionMineTable, {
    attachTo: document.body,
    props: { requests },
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

describe('我的调代申请列表', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Element Plus 把消息挂到 body 上；清掉上一条用例的残留，避免把旧提示当成新结果。
    document.body.innerHTML = ''
    httpMock.get.mockResolvedValue({ data: [] })
  })

  it('显示五种审批状态、驳回理由，以及改人批准时的期望与实际代课人', async () => {
    const wrapper = await mountTable([
      row({ id: 1, status: 'SUBMITTED', expectedSubstituteTeacherName: '李老师' }),
      row({
        id: 2,
        status: 'EFFECTIVE',
        expectedSubstituteTeacherName: '李老师',
        substituteTeacherId: 3,
        substituteTeacherName: '王老师',
      }),
      row({ id: 3, status: 'REJECTED', approvalComment: '该时段已安排教研' }),
      row({ id: 4, status: 'WITHDRAWN' }),
      row({ id: 5, status: 'EXPIRED' }),
      row({ id: 6, status: 'CANCELLED' }),
    ])

    const text = wrapper.text()
    expect(text).toContain('待审批')
    expect(text).toContain('已生效')
    expect(text).toContain('已驳回')
    expect(text).toContain('已撤回')
    expect(text).toContain('已作废')
    expect(text).toContain('已取消')
    expect(text).toContain('该时段已安排教研')
    expect(text).toContain('期望：李老师')
    expect(text).toContain('实际：王老师')
  })

  it('只有待审批的申请能撤回，撤回成功后提示并通知刷新', async () => {
    httpMock.post.mockResolvedValue({ data: { id: 1, status: 'WITHDRAWN' } })
    const wrapper = await mountTable([
      row({ id: 1, status: 'SUBMITTED' }),
      row({ id: 2, status: 'EFFECTIVE' }),
      row({ id: 3, status: 'REJECTED' }),
    ])

    const withdrawButtons = wrapper.findAll('button').filter((item) => item.text() === '撤回')
    expect(withdrawButtons).toHaveLength(1)
    await withdrawButtons[0].trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-requests/1/withdraw')
    expect(document.body.textContent).toContain('已撤回申请')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('撤回失败时显示服务端原因，不当成成功', async () => {
    httpMock.post.mockRejectedValue({ response: { data: { message: '该申请已被处理' } } })
    const wrapper = await mountTable([row({ id: 1, status: 'SUBMITTED' })])

    const withdraw = wrapper.findAll('button').find((item) => item.text() === '撤回')
    await withdraw!.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('该申请已被处理')
    expect(document.body.textContent).not.toContain('已撤回申请')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })
})
