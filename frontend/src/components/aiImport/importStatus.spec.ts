import { describe, expect, it } from 'vitest'
import {
  LOCKED_STATUSES,
  RESTORABLE_STATUSES,
  STATUS_LABELS,
  isAbandoned,
  isCommitted,
  isCommitting,
  isEditable,
  isFailed,
  isLocked,
  isParsing,
  isPartial,
  isRestorable,
  statusTagType,
  statusText,
} from './importStatus'

describe('导入状态', () => {
  it('每种状态都有固定的中文名', () => {
    expect(STATUS_LABELS).toMatchObject({
      PARSING: '解析中',
      COMMITTING: '入库中',
      PARTIAL: '部分结果',
      ABANDONED: '已放弃',
    })
    expect(statusText('PARSING')).toBe('解析中')
    expect(statusText('COMMITTING')).toBe('入库中')
    expect(statusText('PARTIAL')).toBe('部分结果')
    // A status this build does not know yet is shown as-is, never as an empty tag.
    expect(statusText('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('解析中、入库中与部分结果用警示色，失败用危险色', () => {
    expect(statusTagType('PARSING')).toBe('warning')
    expect(statusTagType('COMMITTING')).toBe('warning')
    expect(statusTagType('PARTIAL')).toBe('warning')
    expect(statusTagType('FAILED')).toBe('danger')
    expect(statusTagType('COMMITTED')).toBe('success')
    expect(statusTagType('PREVIEW')).toBe('info')
  })

  it('解析中、入库中、部分结果和已放弃都不允许再改或入库', () => {
    expect([...LOCKED_STATUSES].sort()).toEqual([
      'ABANDONED',
      'COMMITTING',
      'PARSING',
      'PARTIAL',
    ])
    expect(isLocked('PARSING')).toBe(true)
    expect(isLocked('COMMITTING')).toBe(true)
    expect(isLocked('PARTIAL')).toBe(true)
    expect(isLocked('ABANDONED')).toBe(true)
    expect(isLocked('PREVIEW')).toBe(false)
  })

  it('只有解析中、入库中和部分结果能被恢复入口接着看，已放弃的不行', () => {
    expect(isRestorable('PARSING')).toBe(true)
    expect(isRestorable('COMMITTING')).toBe(true)
    expect(isRestorable('PARTIAL')).toBe(true)
    expect(isRestorable('ABANDONED')).toBe(false)
    expect(RESTORABLE_STATUSES.has('ABANDONED')).toBe(false)
  })

  it('给页面一组判断，不用在模板里写状态字面量', () => {
    expect(isParsing('PARSING')).toBe(true)
    expect(isParsing(null)).toBe(false)
    expect(isPartial('PARTIAL')).toBe(true)
    expect(isCommitting('COMMITTING')).toBe(true)
    expect(isAbandoned('ABANDONED')).toBe(true)
    expect(isCommitted('COMMITTED')).toBe(true)
    expect(isFailed('FAILED')).toBe(true)
    expect(isEditable('PREVIEW')).toBe(true)
    expect(isEditable('EDITED')).toBe(true)
    expect(isEditable('PARTIAL')).toBe(false)
  })
})
