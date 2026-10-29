import { describe, expect, it } from 'vitest'
import { evaluateLessonDrop } from './dragTargets'
import type { Entry } from '../types'

/**
 * 票 01：步骤 1 课卡拖拽的目标判定（前端唯一规则源）。
 * 顺序与原因文案由票 01 计划第 3.7 条固定：
 * 空格 → 来源课本身 → 窗口外 → 跨班 → 同班且日期不早于来源日才有效。
 */

function entry(overrides: Partial<Entry> = {}): Entry {
  return {
    id: 101,
    classId: 7,
    className: '高一1班',
    courseId: 11,
    courseName: '语文',
    teacherId: 21,
    teacherName: '张老师',
    periodId: 301,
    periodNo: 1,
    dayOfWeek: 3,
    substituted: false,
    roomName: '教室 A',
    ...overrides,
  }
}

const SOURCE = entry()
const LESSON_DATE = '2026-10-28'

describe('evaluateLessonDrop', () => {
  it('空格：空格只挪不补', () => {
    const verdict = evaluateLessonDrop(SOURCE, null, '2026-10-29', LESSON_DATE)
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('空格：空格只挪不补，不是有效调课目标')
  })

  it('来源课本身不能作为调课目标', () => {
    const verdict = evaluateLessonDrop(SOURCE, entry(), LESSON_DATE, LESSON_DATE)
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('来源课本身不能作为调课目标')
  })

  it('目标日期早于来源日期 → 窗口外', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 102, periodNo: 2, className: '高一1班' }),
      '2026-10-27',
      LESSON_DATE,
    )
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe(
      '窗口外：调课窗口从来源日期向后展开，不支持向来源日期之前搜索',
    )
  })

  it('跨班目标不可投放，原因里点出目标班级', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 103, classId: 8, className: '高一2班', periodNo: 2 }),
      '2026-10-29',
      LESSON_DATE,
    )
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('跨班：目标格是高一2班的课，不是同一班，不能对调')
  })

  it('同名班级但 classId 不同 → 仍判跨班（身份看 classId）', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 107, classId: 99, className: '高一1班', periodNo: 2 }),
      '2026-10-29',
      LESSON_DATE,
    )
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('跨班：目标格是高一1班的课，不是同一班，不能对调')
  })

  it('classId 相同、展示名不同 → 视为同班，有效', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 106, periodNo: 2, className: '高一1班（已改名）' }),
      '2026-10-29',
      LESSON_DATE,
    )
    expect(verdict).toEqual({ ok: true })
  })

  it('同班且目标日期等于来源日期 → 有效', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 104, periodNo: 3 }),
      LESSON_DATE,
      LESSON_DATE,
    )
    expect(verdict).toEqual({ ok: true })
  })

  it('同班且目标日期晚于来源日期 → 有效（窗口最后一天同样有效）', () => {
    const verdict = evaluateLessonDrop(
      SOURCE,
      entry({ id: 105, periodNo: 2, dayOfWeek: 2 }),
      '2026-11-03',
      LESSON_DATE,
    )
    expect(verdict).toEqual({ ok: true })
  })
})
