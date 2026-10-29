import { describe, expect, it } from 'vitest'
import { evaluateDropCell, mondayOf, weekDates, weekdayLabel } from './swapTargetRules'
import type { SwapTargetSlot } from './types'

/**
 * 票 06：拖拽目标格合法性判定（前端唯一规则源）。
 * 规则必须与表单入口同源：日期早于来源日＝窗口外（表单日期选择器同一表达式）；
 * 槽位来自后端 swap-targets（表单课节下拉同一份数据）；来源课本身与空格不可投放。
 */

// 2026-03-04 是周三：来源课「周三第 1 节」。
const LESSON_DATE = '2026-03-04'
const SOURCE_PERIOD_NO = 1

function okDay(slots: SwapTargetSlot[]) {
  return { status: 'ok' as const, slots }
}

describe('mondayOf / weekDates / weekdayLabel', () => {
  it('把任意日期归到所在周的周一', () => {
    expect(mondayOf('2026-03-04')).toBe('2026-03-02')
    expect(mondayOf('2026-03-02')).toBe('2026-03-02')
    expect(mondayOf('2026-03-08')).toBe('2026-03-02')
  })

  it('weekDates 返回周一到周五的实际日期', () => {
    expect(weekDates('2026-03-02')).toEqual([
      '2026-03-02',
      '2026-03-03',
      '2026-03-04',
      '2026-03-05',
      '2026-03-06',
    ])
  })

  it('weekdayLabel 返回中文星期名', () => {
    expect(weekdayLabel('2026-03-04')).toBe('周三')
  })
})

describe('evaluateDropCell', () => {
  it('日期早于来源日期 → 窗口外（与表单 disableDate 同一规则）', () => {
    const verdict = evaluateDropCell({
      date: '2026-03-02',
      periodNo: 1,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: { status: 'loading', slots: [] },
    })
    expect(verdict.state).toBe('before-source')
    expect(verdict.reason).toContain('窗口外')
    expect(verdict.reason).toContain('早于来源日期')
    expect(verdict.reason).toContain(LESSON_DATE)
  })

  it('当天槽位尚未加载完成 → loading（不产生误导性原因）', () => {
    const verdict = evaluateDropCell({
      date: '2026-03-05',
      periodNo: 1,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: { status: 'loading', slots: [] },
    })
    expect(verdict.state).toBe('loading')
  })

  it('当天 swap-targets 请求失败 → 展示后端返回的原因（窗口/学期由后端裁决）', () => {
    const backendMessage = '目标日期 2026-03-10 不在来源日期起 7 天的搜索窗口内'
    const verdict = evaluateDropCell({
      date: '2026-03-06',
      periodNo: 2,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: { status: 'error', slots: [], error: backendMessage },
    })
    expect(verdict.state).toBe('error')
    expect(verdict.reason).toBe(backendMessage)
  })

  it('来源课本身的位置 → 不可作为目标（对应后端「目标课节就是来源课本身」）', () => {
    const verdict = evaluateDropCell({
      date: LESSON_DATE,
      periodNo: SOURCE_PERIOD_NO,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: okDay([]),
    })
    expect(verdict.state).toBe('source')
    expect(verdict.reason).toContain('来源课本身')
  })

  it('同班当天该槽没有课 → 空格只挪不补（对应表单空日提示）', () => {
    const verdict = evaluateDropCell({
      date: '2026-03-05',
      periodNo: 2,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: okDay([]),
    })
    expect(verdict.state).toBe('empty')
    expect(verdict.reason).toContain('空格')
  })

  it('同班当天该槽有课 → 可投放，并带回目标槽位', () => {
    const slot: SwapTargetSlot = {
      periodId: 901,
      periodNo: 1,
      courseId: 11,
      courseName: '数学',
      teacherId: 22,
      teacherName: '王老师',
    }
    const verdict = evaluateDropCell({
      date: '2026-03-06',
      periodNo: 1,
      lessonDate: LESSON_DATE,
      sourcePeriodNo: SOURCE_PERIOD_NO,
      day: okDay([slot]),
    })
    expect(verdict.state).toBe('valid')
    expect(verdict.slot).toBe(slot)
  })
})
