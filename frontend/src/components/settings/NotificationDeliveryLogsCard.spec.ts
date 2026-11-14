import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NotificationDeliveryLogsCard from './NotificationDeliveryLogsCard.vue'
import type {
  NotificationDeliveryLog,
  NotificationDeliveryLogPage,
} from '@/api/notificationDeliveryLogs'

const listMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/notificationDeliveryLogs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notificationDeliveryLogs')>()),
  listNotificationDeliveryLogs: listMock,
}))

function pageOf(items: NotificationDeliveryLogPage['items']): NotificationDeliveryLogPage {
  return { items, total: items.length, page: 0, size: 20 }
}

function deliveryLog(overrides: Partial<NotificationDeliveryLog> = {}): NotificationDeliveryLog {
  return {
    id: 1,
    channel: 'dingtalk',
    platform: 'dingtalk',
    recipient: 'dd-001',
    recipientLabel: '张老师',
    templateCode: 'SUBSTITUTION_EFFECTIVE_SUBSTITUTE',
    content: '周一第 1 节 数学课由李老师代课',
    success: true,
    createdAt: '2026-11-13T01:02:03Z',
    errorMessage: null,
    ...overrides,
  }
}

async function mountCard() {
  const wrapper = mount(NotificationDeliveryLogsCard, {
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

async function expand(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('[data-testid="notification-delivery-logs-collapse"] .el-collapse-item__header').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('NotificationDeliveryLogsCard', () => {
  it('折叠前不请求，展开后按 20 条读取并显示服务端安全标签', async () => {
    listMock.mockResolvedValue(
      pageOf([
        deliveryLog(),
      ]),
    )
    const wrapper = await mountCard()

    expect(listMock).not.toHaveBeenCalled()
    await expand(wrapper)

    expect(listMock).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.text()).toContain('张老师')
    expect(wrapper.text()).toContain('类型')
    expect(wrapper.text()).toContain('代课通知')
    expect(wrapper.text()).not.toContain('SUBSTITUTION_EFFECTIVE_SUBSTITUTE')
    expect(wrapper.text()).toContain('周一第 1 节 数学课由李老师代课')
    expect(wrapper.text()).toContain('钉钉已受理')
    expect(wrapper.text()).toContain('“钉钉已受理”表示钉钉已接受发送请求')
    expect(wrapper.text()).toContain('“系统已保存”表示通知已写入站内记录')
    expect(wrapper.text()).toContain('不代表对方已实际收到或已读')
    wrapper.unmount()
  })

  it('把已知模板代码显示为业务类型，未知代码保留原值', async () => {
    listMock.mockResolvedValue(
      pageOf([
        deliveryLog({
          id: 1,
          templateCode: 'SUBSTITUTION_APPROVAL_APPROVED',
          content: '批准通知',
        }),
        deliveryLog({
          id: 2,
          templateCode: 'SUBSTITUTION_APPROVAL_REJECTED',
          content: '驳回通知',
        }),
        deliveryLog({
          id: 3,
          templateCode: 'SUBSTITUTION_SWAP_EFFECTIVE',
          content: '调课通知',
        }),
        deliveryLog({
          id: 4,
          templateCode: 'FUTURE_TEMPLATE_CODE',
          content: '未知代码原值',
        }),
      ]),
    )
    const wrapper = await mountCard()
    await expand(wrapper)

    const rows = wrapper.findAll('.el-table__row')
    const rowFor = (content: string) => rows.find((row) => row.text().includes(content))!
    expect(rowFor('批准通知').text()).toContain('审批批准')
    expect(rowFor('驳回通知').text()).toContain('审批驳回')
    expect(rowFor('调课通知').text()).toContain('调课通知')
    expect(rowFor('未知代码原值').text()).toContain('FUTURE_TEMPLATE_CODE')
    wrapper.unmount()
  })

  it('依 channel 显示成功原因，失败永远显示失败且不透出原始错误', async () => {
    listMock.mockResolvedValue(
      pageOf([
        deliveryLog({
          id: 1,
          channel: 'local',
          platform: 'system',
          content: '本地成功',
        }),
        deliveryLog({
          id: 2,
          channel: 'webhook',
          platform: '',
          content: '其他渠道成功',
        }),
        deliveryLog({
          id: 3,
          channel: 'dingtalk',
          platform: 'dingtalk',
          content: '钉钉失败',
          success: false,
          errorMessage: 'vendor-raw-error-must-not-render',
        }),
      ]),
    )
    const wrapper = await mountCard()
    await expand(wrapper)

    const rows = wrapper.findAll('.el-table__row')
    const localRow = rows.find((row) => row.text().includes('本地成功'))!
    const otherRow = rows.find((row) => row.text().includes('其他渠道成功'))!
    const failedRow = rows.find((row) => row.text().includes('钉钉失败'))!
    expect(localRow.text()).toContain('系统已保存')
    expect(otherRow.text()).toContain('已受理')
    expect(otherRow.text()).toContain('webhook')
    expect(failedRow.text()).toContain('失败')
    expect(failedRow.text()).not.toContain('钉钉已受理')
    expect(wrapper.text()).not.toContain('vendor-raw-error-must-not-render')
    wrapper.unmount()
  })

  it('分页固定每页 20 条，并把界面页码转换为接口的零基页码', async () => {
    listMock.mockResolvedValue({
      items: [deliveryLog()],
      total: 41,
      page: 0,
      size: 20,
    })
    const wrapper = await mountCard()
    await expand(wrapper)

    const pager = wrapper.findComponent({ name: 'ElPagination' })
    expect(pager.props('pageSize')).toBe(20)
    pager.vm.$emit('current-change', 2)
    await flushPromises()

    expect(listMock).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    wrapper.unmount()
  })

  it('显示加载、空态和固定失败说明，重新展开可重试', async () => {
    let rejectFirst!: (error: Error) => void
    listMock
      .mockReturnValueOnce(
        new Promise((_resolve, reject) => {
          rejectFirst = reject
        }),
      )
      .mockResolvedValueOnce(pageOf([]))
    const wrapper = await mountCard()
    await expand(wrapper)

    expect(wrapper.find('.el-loading-mask').exists()).toBe(true)
    rejectFirst(new Error('raw-network-error'))
    await flushPromises()

    expect(wrapper.text()).toContain('加载通知发送记录失败，请稍后重试。')
    expect(wrapper.text()).not.toContain('raw-network-error')

    await wrapper.get('[data-testid="notification-delivery-logs-collapse"] .el-collapse-item__header').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="notification-delivery-logs-collapse"] .el-collapse-item__header').trigger('click')
    await flushPromises()

    expect(listMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有通知发送记录')
    wrapper.unmount()
  })
})
