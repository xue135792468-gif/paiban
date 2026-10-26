import { describe, expect, it } from 'vitest'
import {
  applyResultOf,
  applyResultText,
  blockingIssues,
  blockingSummary,
  classMappingOf,
  issueRow,
  issueRows,
  previewBlockedReason,
  templateOptionLabel,
} from './templateModel'
import type { ConstraintConfigDto, TemplateApplyPreview, TemplateReferenceIssue } from './types'

function issue(overrides: Partial<TemplateReferenceIssue> = {}): TemplateReferenceIssue {
  return {
    type: 'CLASS_MAX_PER_DAY',
    typeName: '班级一天最多 N 节',
    position: 2,
    field: 'classId',
    fieldLabel: '班级',
    objectId: 31,
    name: '高一1班',
    reason: '行政班属于其他学期（2025 秋），请显式指定替代班级',
    ...overrides,
  }
}

const TEACHER_ISSUE = issue({
  type: 'TEACHER_UNAVAILABLE',
  typeName: '教师禁排时段',
  position: 1,
  field: 'teacherId',
  fieldLabel: '教师',
  objectId: 8,
  name: null,
  reason: '教师不存在或已被删除',
})

function config(entries: number): ConstraintConfigDto {
  return {
    type: 'TEACHER_UNAVAILABLE',
    params: {},
    weight: 100,
    hard: true,
    enabled: true,
    entries: Array.from({ length: entries }, (_unused, index) => ({
      id: index + 1,
      params: { teacherId: 1 },
    })),
  }
}

describe('templateModel', () => {
  it('模板下拉标签同时给出类型数与条目数', () => {
    expect(templateOptionLabel({ id: 1, name: '标准学期', configCount: 12, entryCount: 18 })).toBe(
      '标准学期（12 类 · 18 条）',
    )
  })

  it('套用结果按服务端响应计算类型数与条目数', () => {
    const result = applyResultOf([config(3), config(2), { ...config(0), entries: undefined }])
    expect(result).toEqual({ configCount: 3, entryCount: 5 })
    expect(applyResultText('标准学期', result)).toBe('已套用模板「标准学期」：恢复 3 类配置、5 条条目')
  })

  it('失效引用渲染成一行：类型、位置、字段、对象与原因都不留空', () => {
    const row = issueRow(issue())
    expect(row.typeLabel).toBe('班级一天最多 N 节（CLASS_MAX_PER_DAY）')
    expect(row.location).toBe('第 2 条')
    expect(row.fieldLabel).toBe('班级')
    expect(row.objectLabel).toBe('高一1班（#31）')
    expect(row.rebindable).toBe(true)

    const nameless = issueRow(TEACHER_ISSUE)
    expect(nameless.objectLabel).toBe('#8')
    expect(nameless.rebindable).toBe(false)
  })

  it('位置为 0 的旧格式模板显示为整条配置', () => {
    expect(issueRow(issue({ position: 0 })).location).toBe('整条配置')
  })

  it('只有选定了班级的行政班引用才算处理完，教师/课程永远不会被自动修复', () => {
    const issues = [issue(), TEACHER_ISSUE]
    expect(blockingIssues(issues, {}).length).toBe(2)
    const rowKey = issueRow(issue()).key
    expect(blockingIssues(issues, { [rowKey]: 77 }).length).toBe(1)
    expect(blockingSummary(issues, { [rowKey]: 77 })).toContain('1 处对象（教师/课程）已不存在')
    expect(blockingSummary(issues, {})).toContain('1 处行政班引用还需选择当前学期的班级')
  })

  it('映射只包含用户显式选择的班级，不猜同名', () => {
    const issues = [issue(), issue({ objectId: 32, position: 3 }), TEACHER_ISSUE]
    const mapping = classMappingOf(issues, { [issueRow(issue()).key]: 77 })
    expect(mapping).toEqual({ '31': 77 })
    expect(classMappingOf(issues, {})).toEqual({})
  })

  it('预览报出其他阻止套用的原因，没有原因时返回空', () => {
    const blocked: TemplateApplyPreview = {
      configCount: 1,
      entryCount: 1,
      invalidReferences: [],
      errors: ['教师禁排时段(TEACHER_UNAVAILABLE) 第1 条: 星期6第2节不在本学期作息范围内'],
    }
    expect(previewBlockedReason(blocked)).toContain('不在本学期作息范围内')
    expect(
      previewBlockedReason({ ...blocked, errors: [] }),
    ).toBeNull()
    expect(previewBlockedReason(null)).toBeNull()
  })

  it('多行失效引用按顺序渲染', () => {
    expect(issueRows([issue(), TEACHER_ISSUE]).map((row) => row.location)).toEqual([
      '第 2 条',
      '第 1 条',
    ])
  })
})
