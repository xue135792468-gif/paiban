import { describe, expect, it } from 'vitest'
import {
  LIFECYCLE_ENTITY_LABEL,
  groupRecentDeletions,
  type RecentDeletion,
} from './lifecycle'

function row(overrides: Partial<RecentDeletion> = {}): RecentDeletion {
  return {
    id: 1,
    batchId: 'batch-1',
    entityType: 'CONSTRAINT_RULE',
    entityId: 4,
    entityLabel: '教师不可排：王老师',
    primary: true,
    deletedBy: 'admin',
    deletedAt: '2026-11-01T02:00:00Z',
    expiresAt: '2026-11-08T02:00:00Z',
    restoredAt: null,
    restoredBy: null,
    status: 'PENDING',
    daysLeft: 7,
    ...overrides,
  }
}

describe('lifecycle: extended entity labels', () => {
  it('names every ticket-04 entity family in Chinese', () => {
    expect(LIFECYCLE_ENTITY_LABEL.constraint_rule).toBe('排课规则')
    expect(LIFECYCLE_ENTITY_LABEL.timetable_draft).toBe('课表草稿')
    expect(LIFECYCLE_ENTITY_LABEL.ai_provider).toBe('AI 供应商')
    expect(LIFECYCLE_ENTITY_LABEL.ai_model).toBe('AI 模型')
    expect(LIFECYCLE_ENTITY_LABEL.dingtalk_config).toBe('钉钉配置')
  })

  it('groups a rule-card batch under its sentence label', () => {
    const batches = groupRecentDeletions([row()])
    expect(batches).toHaveLength(1)
    expect(batches[0].label).toBe('教师不可排：王老师')
    expect(batches[0].entityTypes).toEqual(['CONSTRAINT_RULE'])
    expect(batches[0].status).toBe('PENDING')
  })
})
