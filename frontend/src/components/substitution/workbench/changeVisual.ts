import { isoOf, mondayOf, periodNoOf, weekdayLabel } from './swapTargetRules'
import type { ChangeItem, PreviewResponse, SwapPreviewResponse } from './types'

/**
 * 票 02：课表变化可视化的纯函数模型。
 *
 * 输入只有后端 DTO（Cover 的 PreviewResponse / Swap 的 SwapPreviewResponse），
 * 输出是周表 + 图例的渲染模型：变化格合并（同一格既搬出又搬入）、
 * 编号按（周、日期、节次）排序、一侧缺失写「空出」。
 */

export interface ChangeCell {
  /** 带圈编号（①…⑳，超过 20 用「21.」）。 */
  no: string
  /** 原值 `{课程} {教师}`；一侧缺失时为「空出」。 */
  old: string | null
  /** 改后值 `{课程} {教师}`；一侧缺失时为「空出」。 */
  new: string | null
}

/** 迷你周表；cells 的 key = `${periodNo}-${dayIdx}`，dayIdx 0=周一。 */
export interface WeekTable {
  label: '本周' | '下周'
  range: string
  periodRows: number[]
  cells: Record<string, ChangeCell>
}

export interface VisualModel {
  legend: string[]
  weeks: WeekTable[]
}

const CIRCLED_START = 0x2460 // ①
const CIRCLED_END = 0x2473 // ⑳

/** 1→① … 20→⑳；超出带圈区改写「21.」。 */
export function circledNo(n: number): string {
  if (!Number.isInteger(n) || n < 1) return ''
  if (n <= CIRCLED_END - CIRCLED_START + 1) {
    return String.fromCharCode(CIRCLED_START + n - 1)
  }
  return `${n}.`
}

/** 字符串首字符是否带圈编号（图例高亮与 ChangeVisualizer 共用同一判定）。 */
export function isCircledNo(no: string): boolean {
  const code = no.charCodeAt(0)
  return code >= CIRCLED_START && code <= CIRCLED_END
}

/** dayIdx 0=周一。 */
function dayIndexOf(date: string): number {
  const value = new Date(`${date}T00:00:00`)
  return Number.isNaN(value.getTime()) ? 0 : (value.getDay() + 6) % 7
}

/** 「10/26」样式的月/日（都补零，与列头一致）。 */
function monthDay(date: string): string {
  const [, month, day] = date.split('-')
  return month && day ? `${month}/${day}` : ''
}

/** 该周周一~周五的范围「10/26–10/30」。 */
function weekRange(monday: string): string {
  const start = new Date(`${monday}T00:00:00`)
  if (Number.isNaN(start.getTime())) return ''
  const friday = new Date(start)
  friday.setDate(start.getDate() + 4)
  return `${monthDay(monday)}–${monthDay(isoOf(friday))}`
}

function shiftDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00`)
  if (Number.isNaN(value.getTime())) return ''
  value.setDate(value.getDate() + days)
  return isoOf(value)
}

/** 槽位文案：`本周三第1节` / `下周一第2节`（锚点周 = 来源课所在周）。 */
export function slotLabel(date: string, periodNo: number, anchorMonday: string): string {
  const prefix = mondayOf(date) === anchorMonday ? '本' : '下'
  return `${prefix}${weekdayLabel(date)}第${periodNo}节`
}

/** 格内旧值/新值文本：`{课程} {教师}`（缺教师只写课程）。 */
function cellText(
  courseName: string | null | undefined,
  teacherName: string | null | undefined,
): string {
  return [courseName, teacherName].filter((part) => !!part).join(' ')
}

/** 一次移动：图例按它渲染 `课程：from槽 → to槽`。 */
interface MoveRef {
  course: string
  fromDate: string
  fromPeriodNo: number
  toDate: string
  toPeriodNo: number
}

interface WorkCell {
  old: string | null
  new: string | null
  /** 描述该格的移动：优先「该格原课搬出」这一步，防御性只搬入格退回搬入步骤。 */
  move?: MoveRef
}

const EMPTY_MODEL: VisualModel = { legend: [], weeks: [] }

/**
 * Swap 变化 → 视觉模型。
 * 每个 SwapChangeItemDto 是一次移动：from 格写旧值、to 格写新值；
 * 同班闭环里同一格既是某步的 from 又是另一步的 to，自然合并成 `旧 → 新`。
 */
export function buildSwapVisual(preview: SwapPreviewResponse): VisualModel {
  const changes = preview.changes ?? []
  if (changes.length === 0) return EMPTY_MODEL
  const anchorMonday = mondayOf(preview.lessonDate)
  if (!anchorMonday) return EMPTY_MODEL

  // cells 分两桶：本周（0）与下周（1）；key = `${periodNo}-${dayIdx}`。
  const buckets: Array<Map<string, WorkCell>> = [new Map(), new Map()]
  const weekIndexOf = (date: string) => (mondayOf(date) === anchorMonday ? 0 : 1)

  for (const change of changes) {
    const text = cellText(change.courseName, change.teacherName)
    const move: MoveRef = {
      course: change.courseName ?? '',
      fromDate: change.fromDate,
      fromPeriodNo: periodNoOf(change.fromLabel),
      toDate: change.toDate,
      toPeriodNo: periodNoOf(change.toLabel),
    }
    const fromKey = `${move.fromPeriodNo}-${dayIndexOf(move.fromDate)}`
    const toKey = `${move.toPeriodNo}-${dayIndexOf(move.toDate)}`
    const fromCell = buckets[weekIndexOf(move.fromDate)].get(fromKey) ?? { old: null, new: null }
    // from 覆盖：同一格既搬出又搬入时，图例永远讲「这格的原课搬去了哪」。
    fromCell.old = text
    fromCell.move = move
    buckets[weekIndexOf(move.fromDate)].set(fromKey, fromCell)
    const toCell = buckets[weekIndexOf(move.toDate)].get(toKey) ?? { old: null, new: null }
    toCell.new = text
    if (!toCell.move) toCell.move = move
    buckets[weekIndexOf(move.toDate)].set(toKey, toCell)
  }

  // 编号按（周、日期、节次）排序；图例顺序与编号一致。
  interface Ordered {
    weekIndex: number
    key: string
    cell: ChangeCell
    move?: MoveRef
    date: string
    periodNo: number
  }
  const ordered: Ordered[] = []
  for (const [weekIndex, bucket] of buckets.entries()) {
    for (const [key, work] of bucket) {
      const [periodNo, dayIdx] = key.split('-').map(Number)
      ordered.push({
        weekIndex,
        key,
        move: work.move,
        date: shiftDays(anchorMonday, weekIndex * 7 + dayIdx),
        periodNo,
        cell: { no: '', old: work.old ?? '空出', new: work.new ?? '空出' },
      })
    }
  }
  ordered.sort((a, b) =>
    a.weekIndex !== b.weekIndex
      ? a.weekIndex - b.weekIndex
      : a.date !== b.date
        ? a.date < b.date
          ? -1
          : 1
        : a.periodNo - b.periodNo,
  )

  const legend: string[] = []
  ordered.forEach((item, index) => {
    const no = circledNo(index + 1)
    item.cell.no = no
    if (!item.move) return
    legend.push(
      `${no} ${item.move.course}：${slotLabel(item.move.fromDate, item.move.fromPeriodNo, anchorMonday)} → ${slotLabel(item.move.toDate, item.move.toPeriodNo, anchorMonday)}`,
    )
  })

  const weeks = buckets
    .map((bucket, weekIndex) => {
      if (bucket.size === 0) return null
      const items = ordered.filter((item) => item.weekIndex === weekIndex)
      const periodRows = [...new Set(items.map((item) => item.periodNo))]
        .filter((periodNo) => periodNo > 0)
        .sort((a, b) => a - b)
      const cells: Record<string, ChangeCell> = {}
      for (const item of items) {
        cells[item.key] = item.cell
      }
      return {
        label: weekIndex === 0 ? ('本周' as const) : ('下周' as const),
        range: weekRange(shiftDays(anchorMonday, weekIndex * 7)),
        periodRows,
        cells,
      }
    })
    .filter((week): week is WeekTable => week !== null)

  return { legend, weeks }
}

/** Cover（代课）变化 → 视觉模型：本周一张表一格，图例 `① 课程：旧师 → 新师`。 */
export function buildCoverVisual(preview: PreviewResponse): VisualModel {
  const change: ChangeItem | undefined = preview.changes?.[0]
  if (!change) return EMPTY_MODEL
  const anchorMonday = mondayOf(preview.lessonDate || change.lessonDate)
  if (!anchorMonday) return EMPTY_MODEL
  const periodNo = periodNoOf(change.periodLabelBefore)
  const cell: ChangeCell = {
    no: circledNo(1),
    old: cellText(change.courseName, change.teacherNameBefore),
    new: cellText(change.courseName, change.teacherNameAfter),
  }
  return {
    legend: [
      `${circledNo(1)} ${change.courseName ?? ''}：${change.teacherNameBefore ?? ''} → ${change.teacherNameAfter ?? ''}`,
    ],
    weeks: [
      {
        label: '本周',
        range: weekRange(anchorMonday),
        periodRows: periodNo > 0 ? [periodNo] : [],
        cells: { [`${periodNo}-${dayIndexOf(change.lessonDate)}`]: cell },
      },
    ],
  }
}
