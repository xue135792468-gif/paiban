import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SubstitutionApprovalPanel from './SubstitutionApprovalPanel.vue'
import type { RequestRow, Teacher } from './types'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const TEACHERS: Teacher[] = [
  { id: 2, name: '李老师' },
  { id: 3, name: '王老师' },
]

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
    reason: '家中有事',
    createdAt: '2026-11-11T09:00:00Z',
    expectedSubstituteTeacherId: 2,
    expectedSubstituteTeacherName: '李老师',
    ...overrides,
  }
}

async function mountPanel(requests: RequestRow[], canApprove = true): Promise<VueWrapper> {
  const wrapper = mount(SubstitutionApprovalPanel, {
    attachTo: document.body,
    props: { requests, teachers: TEACHERS, canApprove },
    // jsdom 里 ElSelect 的弹层会递归更新（同 RuleGroupSection）；这里只关心
    // 选中值与提交请求，真实下拉交互由 Playwright 覆盖。对话框 teleport 到 body，
    // 断言统一走 document。
    global: {
      plugins: [ElementPlus],
      stubs: {
        ElSelect: {
          name: 'ElSelect',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div class="el-select-stub" />',
        },
        ElOption: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

function button(wrapper: VueWrapper, testId: string) {
  const found = wrapper.find(`[data-testid="${testId}"]`)
  if (!found.exists()) throw new Error(`button not found: ${testId}`)
  return found
}

function dom(testId: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  if (!found) throw new Error(`element not found: ${testId}`)
  return found
}

async function setTextarea(testId: string, value: string) {
  const root = dom(testId)
  const field = (root.tagName === 'TEXTAREA' ? root : root.querySelector('textarea')) as
    | HTMLTextAreaElement
    | null
  if (!field) throw new Error(`textarea not found in ${testId}`)
  field.value = value
  field.dispatchEvent(new Event('input'))
  await flushPromises()
}

async function clickDom(testId: string) {
  dom(testId).click()
  await flushPromises()
}

describe('待审批面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('只列待审批申请，并写明期望代课人', async () => {
    const wrapper = await mountPanel([
      row({ id: 1, expectedSubstituteTeacherName: '李老师' }),
      row({ id: 2, status: 'EFFECTIVE', substituteTeacherName: '王老师' }),
    ])

    const text = wrapper.text()
    expect(text).toContain('李老师')
    expect(text).toContain('家中有事')
    expect(text).not.toContain('王老师')
    expect(wrapper.find('[data-testid="approve-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve-2"]').exists()).toBe(false)
  })

  it('批准默认用期望人，可改人并附审批意见', async () => {
    httpMock.post.mockResolvedValue({ data: { id: 1, status: 'EFFECTIVE' } })
    const wrapper = await mountPanel([row({ id: 1 })])

    await button(wrapper, 'approve-1').trigger('click')
    await flushPromises()

    const select = wrapper.findComponent({ name: 'ElSelect' })
    expect(select.props('modelValue')).toBe(2)
    select.vm.$emit('update:modelValue', 3)
    await setTextarea('approve-comment', '同意，改由王老师')
    await clickDom('approve-confirm')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-requests/1/approve', {
      substituteTeacherId: 3,
      comment: '同意，改由王老师',
    })
    expect(document.body.textContent).toContain('已批准')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('驳回必须填理由，填了才提交', async () => {
    httpMock.post.mockResolvedValue({ data: { id: 1, status: 'REJECTED' } })
    const wrapper = await mountPanel([row({ id: 1 })])

    await button(wrapper, 'reject-1').trigger('click')
    await flushPromises()
    await clickDom('reject-confirm')
    await flushPromises()

    expect(httpMock.post).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写驳回理由')

    await setTextarea('reject-reason', '该时段已安排教研')
    await clickDom('reject-confirm')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-requests/1/reject', {
      reason: '该时段已安排教研',
    })
    expect(document.body.textContent).toContain('已驳回')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('没有审批权限时看不到任何审批动作', async () => {
    const wrapper = await mountPanel([row({ id: 1 })], false)

    expect(wrapper.findAll('[data-testid^="approve-"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid^="reject-"]')).toHaveLength(0)
  })
})
