import { describe, expect, it } from 'vitest'
import {
  applyItem,
  clock,
  dayLabel,
  describeItem,
  keyOf,
  proposedItems,
  sectionLabel,
  timeRange,
  type MissingPeriodItem,
  type MissingPeriodsPlan,
} from './missingPeriods'

function proposed(overrides: Partial<MissingPeriodItem> = {}): MissingPeriodItem {
  return {
    dayOfWeek: 1,
    periodNo: 9,
    status: 'PROPOSED',
    label: '第9节',
    section: 'AFTERNOON',
    startTime: '17:40:00',
    endTime: '18:25:00',
    reason: null,
    ...overrides,
  }
}

function manual(overrides: Partial<MissingPeriodItem> = {}): MissingPeriodItem {
  return {
    dayOfWeek: 6,
    periodNo: 9,
    status: 'MANUAL',
    label: '第9节',
    section: null,
    startTime: null,
    endTime: null,
    reason: '没有可参照的作息时间，请到作息管理手工设置',
    ...overrides,
  }
}

describe('作息缺节展示', () => {
  it('把后端的时间裁成时分并拼出起止区间', () => {
    expect(clock('08:00:00')).toBe('08:00')
    expect(clock('17:40')).toBe('17:40')
    expect(clock(null)).toBe('—')
    expect(timeRange(proposed())).toBe('17:40–18:25')
    expect(timeRange(manual())).toBe('—')
  })

  it('星期与时段用中文展示', () => {
    expect(dayLabel(1)).toBe('周一')
    expect(dayLabel(7)).toBe('周日')
    expect(sectionLabel('AFTERNOON')).toBe('下午')
    expect(sectionLabel(null)).toBe('—')
  })

  it('只把可推算项列为可确认，人工项不在其中', () => {
    const plan: MissingPeriodsPlan = {
      semesterId: 3,
      semesterName: '2026 秋',
      published: false,
      items: [proposed(), manual()],
    }

    expect(proposedItems(plan).map(keyOf)).toEqual(['1-9'])
    expect(proposedItems(null)).toEqual([])
  })

  it('提交体只带时段与时间，不带展示文案', () => {
    expect(applyItem(proposed())).toEqual({
      dayOfWeek: 1,
      periodNo: 9,
      startTime: '17:40:00',
      endTime: '18:25:00',
    })
    expect(applyItem(manual())).toBeNull()
  })

  it('确认对话框里逐项写清星期、节次与起止时间', () => {
    expect(describeItem(proposed())).toBe('周一 第9节 下午 17:40–18:25')
  })
})
