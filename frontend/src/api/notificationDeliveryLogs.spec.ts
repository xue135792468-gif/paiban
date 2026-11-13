import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({ http: { get: getMock } }))

import { listNotificationDeliveryLogs } from './notificationDeliveryLogs'

describe('listNotificationDeliveryLogs', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('只读查询发送记录分页，不附带学期或用户筛选', async () => {
    const page = {
      items: [],
      total: 0,
      page: 2,
      size: 20,
    }
    getMock.mockResolvedValue({ data: page })

    await expect(listNotificationDeliveryLogs({ page: 2, size: 20 })).resolves.toEqual(page)
    expect(getMock).toHaveBeenCalledWith('/notification-delivery-logs', {
      params: { page: 2, size: 20 },
    })
  })
})
