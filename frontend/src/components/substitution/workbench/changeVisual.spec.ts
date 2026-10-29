import { describe, expect, it } from 'vitest'
import {
  buildCoverVisual,
  buildSwapVisual,
  circledNo,
  isCircledNo,
  slotLabel,
} from './changeVisual'
import type { ChangeItem, PreviewResponse, SwapChangeItem, SwapPreviewResponse } from './types'

/**
 * 票 02：课表变化可视化纯函数。
 * 变化格合并（同格既搬出又搬入）、编号按（周、日期、节次）排序、
 * 图例与编号一致、跨周拆两张周表、circledNo 边界与 slotLabel 跨周判断。
 */

// 2026-10-26 是周一；来源课固定为 2026-10-28（周三）。
const ANCHOR_MONDAY = '2026-10-26'
const LESSON_DATE = '2026-10-28'

function coverPreview(changes: ChangeItem[]): PreviewResponse {
  return {
    lessonDate: LESSON_DATE,
    changes,
  } as unknown as PreviewResponse
}

function swapPreview(changes: SwapChangeItem[]): SwapPreviewResponse {
  return {
    lessonDate: LESSON_DATE,
    changes,
  } as unknown as SwapPreviewResponse
}

function coverChange(overrides: Partial<ChangeItem>): ChangeItem {
  return {
    lessonDate: LESSON_DATE,
    periodLabelBefore: '周三第1节',
    periodLabelAfter: '周三第1节',
    courseName: '语文',
    teacherNameBefore: '张老师',
    teacherNameAfter: '王老师',
    ...overrides,
  } as ChangeItem
}

function move(overrides: Partial<SwapChangeItem>): SwapChangeItem {
  return {
    fromDate: '2026-10-28',
    fromLabel: '周三第1节',
    toDate: '2026-10-30',
    toLabel: '周五第1节',
    courseName: '语文',
    teacherName: '张老师',
    ...overrides,
  } as SwapChangeItem
}

describe('circledNo', () => {
  it('1→①、10→⑩、20→⑳、21 超出带圈区改用「21.」', () => {
    expect(circledNo(1)).toBe('①')
    expect(circledNo(10)).toBe('⑩')
    expect(circledNo(20)).toBe('⑳')
    expect(circledNo(21)).toBe('21.')
  })

  it('isCircledNo 只认带圈区间的首字符（「21.」不算带圈编号）', () => {
    expect(isCircledNo('①')).toBe(true)
    expect(isCircledNo('⑳')).toBe(true)
    expect(isCircledNo('21.')).toBe(false)
  })
})

describe('slotLabel', () => {
  it('锚点周内写「本周」，下一周写「下周」', () => {
    expect(slotLabel('2026-10-28', 1, ANCHOR_MONDAY)).toBe('本周三第1节')
    expect(slotLabel('2026-10-30', 2, ANCHOR_MONDAY)).toBe('本周五第2节')
    expect(slotLabel('2026-11-02', 2, ANCHOR_MONDAY)).toBe('下周一第2节')
  })
})

describe('buildCoverVisual', () => {
  it('代课：本周一张表、一格旧师→新师、图例一项', () => {
    const model = buildCoverVisual(
      coverPreview([coverChange({})]),
    )
    expect(model.weeks).toHaveLength(1)
    const week = model.weeks[0]
    expect(week.label).toBe('本周')
    expect(week.range).toBe('10/26–10/30')
    expect(week.periodRows).toEqual([1])
    const cell = week.cells['1-2']
    expect(cell).toBeDefined()
    expect(cell.no).toBe('①')
    expect(cell.old).toBe('语文 张老师')
    expect(cell.new).toBe('语文 王老师')
    expect(model.legend).toEqual(['① 语文：张老师 → 王老师'])
  })
})

describe('buildSwapVisual', () => {
  it('同周二人对调：合并成两格，旧→新方向与接口一致', () => {
    const model = buildSwapVisual(
      swapPreview([
        move({ courseName: '语文', teacherName: '张老师', toDate: '2026-10-30', toLabel: '周五第1节' }),
        move({
          fromDate: '2026-10-30',
          fromLabel: '周五第1节',
          toDate: '2026-10-28',
          toLabel: '周三第1节',
          courseName: '数学',
          teacherName: '王老师',
        }),
      ]),
    )
    expect(model.weeks).toHaveLength(1)
    const week = model.weeks[0]
    expect(week.periodRows).toEqual([1])
    // 周三格：原语文张老师搬走，数学王老师搬入。
    expect(week.cells['1-2']).toMatchObject({ no: '①', old: '语文 张老师', new: '数学 王老师' })
    // 周五格：原数学王老师搬走，语文张老师搬入。
    expect(week.cells['1-4']).toMatchObject({ no: '②', old: '数学 王老师', new: '语文 张老师' })
    expect(model.legend).toEqual([
      '① 语文：本周三第1节 → 本周五第1节',
      '② 数学：本周五第1节 → 本周三第1节',
    ])
  })

  it('跨周对调：本周与下周各一张表，图例两项', () => {
    const model = buildSwapVisual(
      swapPreview([
        move({ toDate: '2026-11-02', toLabel: '周一第1节' }),
        move({
          fromDate: '2026-11-02',
          fromLabel: '周一第1节',
          toDate: '2026-10-28',
          toLabel: '周三第1节',
          courseName: '数学',
          teacherName: '王老师',
        }),
      ]),
    )
    expect(model.weeks).toHaveLength(2)
    expect(model.weeks[0]).toMatchObject({ label: '本周', range: '10/26–10/30' })
    expect(model.weeks[0].cells['1-2']).toMatchObject({ no: '①', old: '语文 张老师', new: '数学 王老师' })
    expect(model.weeks[1]).toMatchObject({ label: '下周', range: '11/02–11/06' })
    expect(model.weeks[1].cells['1-0']).toMatchObject({ no: '②', old: '数学 王老师', new: '语文 张老师' })
    expect(model.legend).toEqual([
      '① 语文：本周三第1节 → 下周一第1节',
      '② 数学：下周一第1节 → 本周三第1节',
    ])
  })

  it('三人环：编号①②③按（日期、节次）排序，图例顺序一致', () => {
    const model = buildSwapVisual(
      swapPreview([
        move({ toDate: '2026-10-29', toLabel: '周四第1节', courseName: '语文', teacherName: '张老师' }),
        move({
          fromDate: '2026-10-29',
          fromLabel: '周四第1节',
          toDate: '2026-10-30',
          toLabel: '周五第1节',
          courseName: '数学',
          teacherName: '王老师',
        }),
        move({
          fromDate: '2026-10-30',
          fromLabel: '周五第1节',
          toDate: '2026-10-28',
          toLabel: '周三第1节',
          courseName: '英语',
          teacherName: '赵老师',
        }),
      ]),
    )
    expect(model.weeks).toHaveLength(1)
    const cells = model.weeks[0].cells
    expect(cells['1-2']).toMatchObject({ no: '①', old: '语文 张老师', new: '英语 赵老师' })
    expect(cells['1-3']).toMatchObject({ no: '②', old: '数学 王老师', new: '语文 张老师' })
    expect(cells['1-4']).toMatchObject({ no: '③', old: '英语 赵老师', new: '数学 王老师' })
    expect(model.legend).toEqual([
      '① 语文：本周三第1节 → 本周四第1节',
      '② 数学：本周四第1节 → 本周五第1节',
      '③ 英语：本周五第1节 → 本周三第1节',
    ])
  })

  it('periodRows 去重升序：同周第 2、1 节都有变化时行序为 [1, 2]', () => {
    const model = buildSwapVisual(
      swapPreview([
        move({ fromLabel: '周三第2节', toLabel: '周五第2节' }),
        move({
          fromDate: '2026-10-30',
          fromLabel: '周五第2节',
          toDate: '2026-10-28',
          toLabel: '周三第2节',
          courseName: '数学',
          teacherName: '王老师',
        }),
        move({
          fromDate: '2026-10-29',
          fromLabel: '周四第1节',
          toDate: '2026-10-30',
          toLabel: '周五第1节',
          courseName: '英语',
          teacherName: '赵老师',
        }),
        move({
          fromDate: '2026-10-30',
          fromLabel: '周五第1节',
          toDate: '2026-10-29',
          toLabel: '周四第1节',
          courseName: '英语',
          teacherName: '钱老师',
        }),
      ]),
    )
    expect(model.weeks[0].periodRows).toEqual([1, 2])
  })

  it('只搬入无人搬出的格（防御）：缺失一侧写「空出」', () => {
    const model = buildSwapVisual(
      swapPreview([
        move({ courseName: '语文', teacherName: '张老师', toDate: '2026-10-30', toLabel: '周五第1节' }),
      ]),
    )
    expect(model.weeks[0].cells['1-2']).toMatchObject({ no: '①', old: '语文 张老师', new: '空出' })
    expect(model.weeks[0].cells['1-4']).toMatchObject({ no: '②', old: '空出', new: '语文 张老师' })
  })

  it('空变化列表返回空模型', () => {
    expect(buildSwapVisual(swapPreview([]))).toEqual({ legend: [], weeks: [] })
  })
})
