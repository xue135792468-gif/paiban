/**
 * 票 01：周课表的日期工具（原票 06 的拖拽判定已迁到 dragTargets.ts）。
 *
 * 周课表列头 = 所选日期所在周的周一~周五；拖拽判定不再依赖后端槽位缓存，
 * 只做「来源卡 vs 目标格」的轻量预选，真正的可行性由步骤 2 的表单与后端裁决。
 */

/** 把任意日期归到所在周的周一（ISO 字符串）。 */
export function mondayOf(date: string): string {
  const value = new Date(`${date}T00:00:00`)
  if (Number.isNaN(value.getTime())) return ''
  value.setDate(value.getDate() - ((value.getDay() + 6) % 7))
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
}

/** 周课表的五个列头日期（周一到周五）。 */
export function weekDates(weekStart: string): string[] {
  const start = new Date(`${weekStart}T00:00:00`)
  if (Number.isNaN(start.getTime())) return []
  return Array.from({ length: 5 }, (_, index) => {
    const day = new Date(start)
    day.setDate(start.getDate() + index)
    const month = String(day.getMonth() + 1).padStart(2, '0')
    const dayNo = String(day.getDate()).padStart(2, '0')
    return `${day.getFullYear()}-${month}-${dayNo}`
  })
}

/** 列头中文星期名。 */
export function weekdayLabel(date: string): string {
  const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const day = new Date(`${date}T00:00:00`).getDay()
  return names[day] ?? `周${day}`
}

/** Date 对象 → 本地时区 ISO 日期。 */
export function isoOf(value: Date): string {
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
}

/** 今天的 ISO 日期。 */
export function today(): string {
  return isoOf(new Date())
}

/** weekStart 所在周里第 dayOfWeek 天（1~7）的 ISO 日期；非法输入返回空串。 */
export function dateOfDay(weekStartIso: string, dayOfWeek: number): string {
  const value = new Date(`${weekStartIso}T00:00:00`)
  if (Number.isNaN(value.getTime())) return ''
  value.setDate(value.getDate() + dayOfWeek - 1)
  return isoOf(value)
}

/** 「11月2日」样式的中文日期（月/日不补零）。 */
export function monthDayCn(iso: string): string {
  const [, month, day] = iso.split('-')
  return `${Number(month)}月${Number(day)}日`
}

/** 从「周三第1节」这类节次标签里解析节次号；解析不出返回 0。 */
export function periodNoOf(label: string | null | undefined): number {
  const match = /第(\d+)节/.exec(label ?? '')
  return match ? Number(match[1]) : 0
}
