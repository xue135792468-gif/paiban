import { describe, expect, it } from 'vitest'
import { buildSwapWindow, windowCaption } from './windowDates'

/**
 * 票 01：调课窗口日期块。
 * 窗口 = 来源课日期起含当天共 windowDays 天（默认 7，与后端
 * substitution.search-window-days 的默认值一致）；周末不可选并给出原因；
 * 相对标签按「今天/明天/后天/下周」的优先级展示。
 */

// 2026-10-28 是周三；该周周一是 2026-10-26。
const LESSON_DATE = '2026-10-28'
const TODAY = '2026-10-28'

describe('buildSwapWindow', () => {
  it('从来源日期起含当天生成 7 个 chip，最后一天是 11/03', () => {
    const chips = buildSwapWindow(LESSON_DATE, TODAY)
    expect(chips.map((chip) => chip.date)).toEqual([
      '2026-10-28',
      '2026-10-29',
      '2026-10-30',
      '2026-10-31',
      '2026-11-01',
      '2026-11-02',
      '2026-11-03',
    ])
    expect(chips[0]).toMatchObject({
      dayLabel: '周三',
      monthDay: '10/28',
      relative: '今天',
      weekend: false,
      disabled: false,
    })
    expect(chips[1].relative).toBe('明天')
    expect(chips[2].relative).toBe('后天')
    expect(chips[5].relative).toBe('下周')
    expect(chips[6].relative).toBe('下周')
  })

  it('窗口边界：包含最后一天，不包含第 8 天', () => {
    const chips = buildSwapWindow(LESSON_DATE, TODAY, 3)
    expect(chips.map((chip) => chip.date)).toEqual([
      '2026-10-28',
      '2026-10-29',
      '2026-10-30',
    ])
  })

  it('周末 chip 置灰并注明「周末没有课」', () => {
    const chips = buildSwapWindow(LESSON_DATE, TODAY)
    const saturday = chips.find((chip) => chip.date === '2026-10-31')!
    const sunday = chips.find((chip) => chip.date === '2026-11-01')!
    expect(saturday).toMatchObject({
      dayLabel: '周六',
      monthDay: '10/31',
      weekend: true,
      disabled: true,
      reason: '周末没有课',
    })
    expect(sunday).toMatchObject({
      dayLabel: '周日',
      monthDay: '11/01',
      weekend: true,
      disabled: true,
      reason: '周末没有课',
    })
    expect(chips.filter((chip) => !chip.weekend).every((chip) => !chip.disabled)).toBe(true)
  })

  it('今天不在窗口内时按「下周」与工作日正常标记', () => {
    // today 是周五 2026-10-30；窗口从周三 11/04 起。
    const chips = buildSwapWindow('2026-11-04', '2026-10-30')
    expect(chips[0].relative).toBe('下周')
    expect(chips.every((chip) => chip.disabled === chip.weekend)).toBe(true)
  })

  it('跨年日期用纯日期字符串推进，周末判定不漂移', () => {
    const chips = buildSwapWindow('2026-12-30', '2026-12-30')
    expect(chips.map((chip) => chip.date)).toEqual([
      '2026-12-30',
      '2026-12-31',
      '2027-01-01',
      '2027-01-02',
      '2027-01-03',
      '2027-01-04',
      '2027-01-05',
    ])
    expect(chips[2]).toMatchObject({ dayLabel: '周五', monthDay: '01/01' })
    expect(chips[3]).toMatchObject({ dayLabel: '周六', disabled: true, reason: '周末没有课' })
    expect(chips[4]).toMatchObject({ dayLabel: '周日', disabled: true, reason: '周末没有课' })
    expect(chips[5]).toMatchObject({ dayLabel: '周一', disabled: false })
  })

  it('非法来源日期返回空窗口', () => {
    expect(buildSwapWindow('', TODAY)).toEqual([])
    expect(buildSwapWindow('not-a-date', TODAY)).toEqual([])
  })
})

describe('windowCaption', () => {
  it('说明窗口范围与含当天天数', () => {
    expect(windowCaption(LESSON_DATE)).toBe('可调到 10月28日–11月3日（含当天共 7 天）')
  })

  it('支持自定义窗口天数并跨年显示', () => {
    expect(windowCaption('2026-12-30', 5)).toBe('可调到 12月30日–1月3日（含当天共 5 天）')
  })
})
