import { mondayOf } from './swapTargetRules'

/**
 * 票 01：调课窗口日期块的纯计算。
 *
 * 窗口 = 来源课日期起、含当天共 windowDays 天（默认 7）。后端设置项
 * substitution.search-window-days 的默认值同为 7；前端取不到配置接口
 * （/api/settings 需 settings 管理权限），因此固定 7 并在文案里写明。
 * 周末不可选；窗口外的日期不生成 chip。
 */

export interface WindowDateChip {
  date: string
  dayLabel: string
  monthDay: string
  relative?: '今天' | '明天' | '后天' | '下周'
  weekend: boolean
  disabled: boolean
  reason?: string
}

const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function parseDate(date: string): Date | null {
  if (!date) return null
  const value = new Date(`${date}T00:00:00`)
  return Number.isNaN(value.getTime()) ? null : value
}

/** 按本地日历推进天数，避免跨月/跨年与夏令时造成的偏移。 */
function shiftDate(date: string, days: number): string {
  const value = parseDate(date)
  if (!value) return ''
  value.setDate(value.getDate() + days)
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
}

function monthDay(date: string): string {
  const value = parseDate(date)
  if (!value) return ''
  return `${String(value.getMonth() + 1).padStart(2, '0')}/${String(value.getDate()).padStart(2, '0')}`
}

function chineseDate(date: string): string {
  const value = parseDate(date)
  if (!value) return ''
  return `${value.getMonth() + 1}月${value.getDate()}日`
}

function relativeLabel(date: string, today: string): WindowDateChip['relative'] {
  if (!parseDate(today)) return undefined
  if (date === today) return '今天'
  if (date === shiftDate(today, 1)) return '明天'
  if (date === shiftDate(today, 2)) return '后天'
  if (mondayOf(date) > mondayOf(today)) return '下周'
  return undefined
}

/** 生成窗口内的日期 chip；非法来源日期返回空数组。 */
export function buildSwapWindow(
  lessonDate: string,
  today: string,
  windowDays = 7,
): WindowDateChip[] {
  if (!parseDate(lessonDate)) return []
  const days = Number.isFinite(windowDays) ? Math.max(0, Math.floor(windowDays)) : 7
  return Array.from({ length: days }, (_, index) => {
    const date = shiftDate(lessonDate, index)
    const value = parseDate(date)!
    const weekend = value.getDay() === 0 || value.getDay() === 6
    return {
      date,
      dayLabel: WEEKDAY_LABELS[value.getDay()],
      monthDay: monthDay(date),
      relative: relativeLabel(date, today),
      weekend,
      disabled: weekend,
      ...(weekend ? { reason: '周末没有课' } : {}),
    }
  })
}

/** 日期块上方的一句说明：可调到 10月28日–11月3日（含当天共 7 天）。 */
export function windowCaption(lessonDate: string, windowDays = 7): string {
  if (!parseDate(lessonDate) || windowDays < 1) return ''
  const lastDate = shiftDate(lessonDate, Math.floor(windowDays) - 1)
  return `可调到 ${chineseDate(lessonDate)}–${chineseDate(lastDate)}（含当天共 ${Math.floor(windowDays)} 天）`
}
