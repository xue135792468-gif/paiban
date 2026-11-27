import { describe, expect, it } from 'vitest'
import { jobConflictLabel } from './jobStatus'

describe('jobConflictLabel', () => {
  it('失败或取消不伪装成无冲突', () => {
    expect(jobConflictLabel({ status: 'FAILED', score: null })).toBe('未完成')
    expect(jobConflictLabel({ status: 'CANCELLED', score: null })).toBe('未完成')
    expect(jobConflictLabel({ status: 'RUNNING', score: null })).toBe('进行中')
  })

  it('已完成有阻塞或无冲突分别说明', () => {
    expect(jobConflictLabel({ status: 'COMPLETED', score: '0hard/0soft', resultPublishable: false })).toBe(
      '有发布阻塞',
    )
    expect(jobConflictLabel({ status: 'COMPLETED', score: '0hard/0soft', resultPublishable: true })).toBe(
      '无冲突',
    )
  })
})
