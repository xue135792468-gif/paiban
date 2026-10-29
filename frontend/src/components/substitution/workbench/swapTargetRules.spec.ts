import { describe, expect, it } from 'vitest'
import { mondayOf, weekDates, weekdayLabel } from './swapTargetRules'

/**
 * 周课表的日期工具：列头日期与所选日期所在周严格对应。
 * 拖拽目标判定已迁到 dragTargets.spec.ts。
 */

describe('mondayOf / weekDates / weekdayLabel', () => {
  it('把任意日期归到所在周的周一', () => {
    expect(mondayOf('2026-03-04')).toBe('2026-03-02')
    expect(mondayOf('2026-03-02')).toBe('2026-03-02')
    expect(mondayOf('2026-03-08')).toBe('2026-03-02')
    expect(mondayOf('2026-12-31')).toBe('2026-12-28')
  })

  it('weekDates 返回周一到周五的实际日期，跨月也连续', () => {
    expect(weekDates('2026-03-02')).toEqual([
      '2026-03-02',
      '2026-03-03',
      '2026-03-04',
      '2026-03-05',
      '2026-03-06',
    ])
    expect(weekDates('2026-12-28')).toEqual([
      '2026-12-28',
      '2026-12-29',
      '2026-12-30',
      '2026-12-31',
      '2027-01-01',
    ])
  })

  it('weekdayLabel 返回中文星期名', () => {
    expect(weekdayLabel('2026-03-02')).toBe('周一')
    expect(weekdayLabel('2026-03-04')).toBe('周三')
    expect(weekdayLabel('2026-03-06')).toBe('周五')
    expect(weekdayLabel('2026-03-08')).toBe('周日')
  })

  it('非法日期返回空值', () => {
    expect(mondayOf('not-a-date')).toBe('')
    expect(weekDates('')).toEqual([])
  })
})
