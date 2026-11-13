import { http } from './http'

export interface NotificationDeliveryLog {
  id: number
  channel: string
  platform: string
  recipient: string
  recipientLabel: string
  templateCode: string
  content: string
  success: boolean
  createdAt: string
  errorMessage: string | null
}

export interface NotificationDeliveryLogPage {
  items: NotificationDeliveryLog[]
  total: number
  page: number
  size: number
}

export interface NotificationDeliveryLogQuery {
  page: number
  size: number
}

/** 只读通知发送记录；安全标签与固定失败说明由服务端生成。 */
export async function listNotificationDeliveryLogs(
  query: NotificationDeliveryLogQuery,
): Promise<NotificationDeliveryLogPage> {
  const { data } = await http.get<NotificationDeliveryLogPage>('/notification-delivery-logs', {
    params: query,
  })
  return data
}
