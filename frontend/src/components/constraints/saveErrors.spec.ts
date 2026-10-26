import { describe, expect, it } from 'vitest'
import { parseSaveErrors, type NamedType } from './saveErrors'

const TYPES: NamedType[] = [
  { code: 'TEACHER_UNAVAILABLE', name: '教师禁排时段' },
  { code: 'COURSE_NOT_IN_EVENING', name: '课程不排晚自习' },
  { code: 'TEACHER_CONFLICT', name: '教师不冲突' },
]

describe('保存错误定位', () => {
  it('把类型级与第几条的错误分开，条目错误能落到具体卡片', () => {
    const report = parseSaveErrors(
      [
        // 后端真实格式：`显示名(CODE) 第N 条: 详情`（“第2 条”中间有一个空格）。
        '教师禁排时段(TEACHER_UNAVAILABLE) 第2 条: 参数「教师」不能为空',
        '教师禁排时段(TEACHER_UNAVAILABLE) 第2 条: 与第1 条完全相同，重复条目已拒绝',
        // 类型级 legacy 错误只带显示名，不带类型码。
        '课程不排晚自习: 参数「课程」不能为空',
        '未知约束类型: BOGUS',
      ].join('; '),
      TYPES,
    )

    expect(report.general).toEqual(['未知约束类型: BOGUS'])
    expect(report.byEntry).toEqual({
      TEACHER_UNAVAILABLE: {
        2: ['参数「教师」不能为空', '与第1 条完全相同，重复条目已拒绝'],
      },
    })
    expect(report.byType).toEqual({ COURSE_NOT_IN_EVENING: ['参数「课程」不能为空'] })
  })

  it('同一类型多条错误都保留，顺序与后端一致', () => {
    const report = parseSaveErrors(
      '教师禁排时段(TEACHER_UNAVAILABLE) 第1 条: 星期8第2节不在本学期作息范围内; ' +
        '教师禁排时段(TEACHER_UNAVAILABLE) 第3 条: 参数「教师」指向不存在的教师: 99',
      TYPES,
    )
    expect(report.byEntry.TEACHER_UNAVAILABLE).toEqual({
      1: ['星期8第2节不在本学期作息范围内'],
      3: ['参数「教师」指向不存在的教师: 99'],
    })
  })

  it('无法识别的整句回落到 general，不丢服务端原文', () => {
    expect(parseSaveErrors('网络错误，请重试')).toEqual({
      general: ['网络错误，请重试'],
      byType: {},
      byEntry: {},
      codes: [],
    })
    expect(parseSaveErrors('')).toEqual({ general: [], byType: {}, byEntry: {}, codes: [] })
    // 没有目录时无法把显示名换算成类型码，整句原样保留。
    expect(parseSaveErrors('课程不排晚自习: 参数「课程」不能为空').general).toEqual([
      '课程不排晚自习: 参数「课程」不能为空',
    ])
  })

  it('有错误的类型清单便于左侧导航标红点', () => {
    const report = parseSaveErrors(
      '教师不冲突(TEACHER_CONFLICT): 该类型没有参数，不能配置条目; 未知约束类型: BOGUS',
      TYPES,
    )
    expect(report.codes).toEqual(['TEACHER_CONFLICT'])
    expect(report.byType).toEqual({
      TEACHER_CONFLICT: ['该类型没有参数，不能配置条目'],
    })
  })
})
