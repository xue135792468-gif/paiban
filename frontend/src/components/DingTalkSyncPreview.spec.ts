import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import DingTalkSyncPreview from './DingTalkSyncPreview.vue'
import type { SyncPreview } from '@/api/integration'

function makePreview(overrides: Partial<SyncPreview> = {}): SyncPreview {
  return {
    success: true,
    message: '预演完成：扫描 1 个部门 / 1 人',
    departmentsScanned: 1,
    usersScanned: 1,
    skippedDepartments: 0,
    failedDepartments: 0,
    elapsedMs: 15,
    skippedDepartmentNames: [],
    summary: { create: 1, merge: 0, suspected: 0, skip: 0 },
    departments: [
      {
        department: '教研组',
        items: [
          {
            userId: 'dd-create-1',
            name: '新老师',
            employeeNo: 'T101',
            phone: '13800000001',
            departments: ['教研组'],
            active: true,
            category: 'CREATE',
            reason: '新增教师',
            matchedTeacherId: null,
            matchedTeacherName: null,
            fills: [],
            conflicts: [],
            candidates: [],
          },
        ],
      },
    ],
    scanComplete: true,
    deactivationWarning: null,
    deactivations: [
      {
        accountId: 41,
        accountName: 'zhangsan',
        displayName: '张三',
        teacherId: 301,
        teacherName: '张三',
        dingtalkUserId: 'dd-missing-41',
      },
      {
        accountId: 42,
        accountName: 'lisi',
        displayName: '李四',
        teacherId: 302,
        teacherName: '李四',
        dingtalkUserId: 'dd-missing-42',
      },
    ],
    ...overrides,
  }
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('DingTalkSyncPreview: 待停用账号选择与确认', () => {
  it('完整扫描下默认勾选全部待停用候选，按钮写清导入与停用数，二次确认后上送三字段期望值', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(DingTalkSyncPreview, {
      props: { preview: makePreview(), confirming: false },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('将停用 2')
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('zhangsan')
    expect(wrapper.text()).toContain('李四')

    const confirmBtn = wrapper.get('[data-testid="dingtalk-confirm"]')
    expect(confirmBtn.text()).toContain('确认导入 1 人、停用 2 个账号')

    await confirmBtn.trigger('click')
    await flushPromises()

    const emitted = wrapper.emitted('confirm')
    expect(emitted).toHaveLength(1)
    expect(emitted?.[0]?.[0]).toEqual({
      excludedDepartments: [],
      excludedUserIds: [],
      suspectedDecisions: [],
      deactivationSelections: [
        { accountId: 41, teacherId: 301, dingtalkUserId: 'dd-missing-41' },
        { accountId: 42, teacherId: 302, dingtalkUserId: 'dd-missing-42' },
      ],
    })
    wrapper.unmount()
  })

  it('扫描不完整或缺失 scanComplete 时显示明显警告、不显示将停用 0 误导、不采纳任何待停用候选', async () => {
    // 若误触发停用确认弹窗则会因 reject 中止 emit；正常导入不应受停用确认阻拦
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(DingTalkSyncPreview, {
      props: {
        preview: makePreview({
          scanComplete: false,
          failedDepartments: 2,
          deactivationWarning: '有 2 个部门读取失败，扫描不完整，本次不计算待停用账号。',
          deactivations: [
            {
              accountId: 41,
              accountName: 'zhangsan',
              displayName: '张三',
              teacherId: 301,
              teacherName: '张三',
              dingtalkUserId: 'dd-missing-41',
            },
          ],
        }),
        confirming: false,
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const warning = wrapper.find('[data-testid="dingtalk-deactivation-warning"]')
    expect(warning.exists()).toBe(true)
    expect(warning.text()).toContain('有 2 个部门读取失败，扫描不完整，本次不计算待停用账号。')
    expect(wrapper.find('[data-testid="dingtalk-deactivation-tag"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dingtalk-deactivation-section"]').exists()).toBe(false)

    await wrapper.get('[data-testid="dingtalk-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual({
      excludedDepartments: [],
      excludedUserIds: [],
      suspectedDecisions: [],
      deactivationSelections: [],
    })

    // 缺失 scanComplete 字段时同样不能当作完整扫描
    const missingScanFlag = makePreview({ scanComplete: undefined, deactivationWarning: null })
    delete (missingScanFlag as Partial<SyncPreview>).scanComplete
    await wrapper.setProps({ preview: missingScanFlag })
    await flushPromises()

    expect(wrapper.find('[data-testid="dingtalk-deactivation-warning"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dingtalk-deactivation-tag"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dingtalk-deactivation-section"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('支持逐个排除待停用账号、二次确认取消不提交、仅停用无导入时也可确认', async () => {
    vi.spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm' as never)

    const wrapper = mount(DingTalkSyncPreview, {
      props: {
        preview: makePreview({
          summary: { create: 0, merge: 0, suspected: 0, skip: 0 },
          departments: [],
        }),
        confirming: false,
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('没有可导入的用户。')
    expect(wrapper.text()).toContain('本次没有新增或补齐的教师')

    const confirmBtn = wrapper.get('[data-testid="dingtalk-confirm"]')
    expect(confirmBtn.attributes('disabled')).toBeUndefined()
    expect(confirmBtn.text()).toContain('确认停用 2 个账号')

    // 取消勾选第一个账号 41
    await wrapper.get('[data-testid="dingtalk-deactivate-checkbox-41"] input').trigger('change')
    await flushPromises()

    expect(wrapper.get('[data-testid="dingtalk-deactivation-tag"]').text()).toBe('将停用 1')
    expect(confirmBtn.text()).toContain('确认停用 1 个账号')

    // 第一次点击确认但在弹窗里点取消 -> 不得 emit confirm
    await confirmBtn.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('confirm')).toBeUndefined()

    // 第二次点击确认并点确定 -> 只上送未被排除的 42
    await confirmBtn.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual({
      excludedDepartments: [],
      excludedUserIds: [],
      suspectedDecisions: [],
      deactivationSelections: [
        { accountId: 42, teacherId: 302, dingtalkUserId: 'dd-missing-42' },
      ],
    })

    // 再把 42 也取消勾选 -> 导入 0 且停用 0，按钮禁用
    await wrapper.get('[data-testid="dingtalk-deactivate-checkbox-42"] input').trigger('change')
    await flushPromises()
    expect(confirmBtn.attributes('disabled')).toBeDefined()
    expect(confirmBtn.text()).toContain('确认导入 0 人')
    wrapper.unmount()
  })

  it('新预演替换旧预演时清空旧的停用排除状态，只按新预演候选生成选择', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(DingTalkSyncPreview, {
      props: { preview: makePreview(), confirming: false },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    // 排除 41
    await wrapper.get('[data-testid="dingtalk-deactivate-checkbox-41"] input').trigger('change')
    await flushPromises()
    expect(wrapper.get('[data-testid="dingtalk-deactivation-tag"]').text()).toBe('将停用 1')

    // 新预演只含 41（且 teacherId/dingtalkUserId 已更新）与新候选 99
    await wrapper.setProps({
      preview: makePreview({
        deactivations: [
          {
            accountId: 41,
            accountName: 'zhangsan',
            displayName: '张三',
            teacherId: 399,
            teacherName: '张三',
            dingtalkUserId: 'dd-new-41',
          },
          {
            accountId: 99,
            accountName: 'wangwu',
            displayName: '王五',
            teacherId: 398,
            teacherName: '王五',
            dingtalkUserId: 'dd-missing-99',
          },
        ],
      }),
    })
    await flushPromises()

    // 旧排除状态已清空，41 和 99 默认都被勾选
    expect(wrapper.get('[data-testid="dingtalk-deactivation-tag"]').text()).toBe('将停用 2')
    await wrapper.get('[data-testid="dingtalk-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual({
      excludedDepartments: [],
      excludedUserIds: [],
      suspectedDecisions: [],
      deactivationSelections: [
        { accountId: 41, teacherId: 399, dingtalkUserId: 'dd-new-41' },
        { accountId: 99, teacherId: 398, dingtalkUserId: 'dd-missing-99' },
      ],
    })
    wrapper.unmount()
  })
})
