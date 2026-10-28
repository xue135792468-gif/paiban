/**
 * 票 06：拖拽目标格合法性判定（前端唯一规则源）。
 *
 * 与表单入口同源，不许两套标准：
 * - 日期早于来源日期 → 窗口外（与表单日期选择器的 disableDate 同一表达式，
 *   也对应后端 AdminSwapService.validateTargetDate 的第一条拒绝）；
 * - 当天可作目标的槽位来自后端 swap-targets（与表单课节下拉同一份数据），
 *   该天请求失败时原样展示后端原因（窗口天数、学期范围由后端裁决）；
 * - 来源课本身、空格不可投放（对应后端 generatePlans 的拒绝原因）。
 * 拖拽投放后仍走与表单完全相同的 swap-plans / swap-previews 请求，
 * 硬约束、开课截止、快照过期与并发冲突都由后端最终裁决。
 */
import type { SwapTargetSlot } from './types'
import { weekdayOf } from '../types'

/** 拖拽目标格的判定结果。 */
export type DropCellState = 'source' | 'valid' | 'empty' | 'before-source' | 'error' | 'loading'

/** 某一天的目标槽位加载状态（swap-targets 的结果缓存）。 */
export interface SwapTargetDayState {
  status: 'loading' | 'ok' | 'error' | 'before-source'
  slots: SwapTargetSlot[]
  error?: string
}

export interface DropCellContext {
  date: string
  periodNo: number
  /** 来源课的实际授课日期（调课窗口从这一天向后展开）。 */
  lessonDate: string
  sourcePeriodNo: number
  /** 该天的槽位状态；日期早于来源日时不会请求，占位即可。 */
  day: SwapTargetDayState
}

export interface DropCellVerdict {
  state: DropCellState
  /** 不可投放时的明确原因（用户可见）。 */
  reason?: string
  /** 可投放时命中的目标槽位。 */
  slot?: SwapTargetSlot
}

export function evaluateDropCell(context: DropCellContext): DropCellVerdict {
  const { date, periodNo, lessonDate, sourcePeriodNo, day } = context
  // 窗口从来源日期向后展开：早于来源日期的格子整格禁用（表单日期选择器同样禁用）。
  if (date < lessonDate) {
    return {
      state: 'before-source',
      reason: `窗口外：目标日期 ${date} 早于来源日期 ${lessonDate}；调课窗口从来源日期向后展开，不支持向来源日期之前搜索`,
    }
  }
  if (day.status === 'loading') {
    return { state: 'loading', reason: '目标槽位加载中' }
  }
  if (day.status === 'error') {
    return { state: 'error', reason: day.error ?? '目标槽位加载失败，请重试' }
  }
  if (date === lessonDate && periodNo === sourcePeriodNo) {
    return { state: 'source', reason: '来源课本身不能作为调课目标' }
  }
  const slot = day.slots.find((item) => item.periodNo === periodNo)
  if (!slot) {
    return { state: 'empty', reason: '空格：空格只挪不补，不是有效调课目标' }
  }
  return { state: 'valid', slot }
}

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
  const names = ['', '周一', '周二', '周三', '周四', '周五', '周六']
  const day = weekdayOf(date)
  return names[day] ?? `周${day}`
}
