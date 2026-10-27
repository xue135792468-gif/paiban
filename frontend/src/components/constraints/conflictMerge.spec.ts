import { describe, expect, it } from 'vitest'
import { mergeConfigs } from './conflictMerge'
import type { ConstraintConfigDto, ConstraintConfigRequest } from './types'

function request(type: string, params: Record<string, unknown> = {}): ConstraintConfigRequest {
  return { type, params, weight: 60, hard: true, enabled: true }
}

function dto(type: string, params: Record<string, unknown> = {}): ConstraintConfigDto {
  return { type, params, weight: 60, hard: true, enabled: true, entries: [] }
}

describe('并发冲突合并', () => {
  it('未改动的类型采用服务端最新版，改过的类型保留我的版本', () => {
    const base = [request('A', { v: 1 }), request('B', { v: 1 })]
    const current = [request('A', { v: 1 }), request('B', { v: 2 })]
    const latest = [dto('A', { v: 9 }), dto('B', { v: 9 })]

    const merged = mergeConfigs(base, current, latest)
    expect(merged.find((config) => config.type === 'A')?.params).toEqual({ v: 9 })
    expect(merged.find((config) => config.type === 'B')?.params).toEqual({ v: 2 })
  })

  it('对方新增的类型并入，我新增的类型保留', () => {
    const base = [request('A')]
    const current = [request('A'), request('MINE')]
    const latest = [dto('A'), dto('THEIRS')]

    const merged = mergeConfigs(base, current, latest)
    expect(merged.map((config) => config.type)).toEqual(['A', 'THEIRS', 'MINE'])
  })

  it('条目的 id、分组与控制一起搬回，不做身份猜测', () => {
    const base: ConstraintConfigRequest[] = []
    const current: ConstraintConfigRequest[] = [
      {
        type: 'COURSE_UNAVAILABLE',
        entries: [
          { id: 7, params: { courseId: 1 }, enabled: false, hard: false, weight: 30, groupKey: 'g' },
        ],
        weight: 100,
        hard: true,
        enabled: true,
      },
    ]
    const merged = mergeConfigs(base, current, [])
    expect(merged).toHaveLength(1)
    expect(merged[0].entries).toEqual([
      {
        id: 7,
        params: { courseId: 1 },
        enabled: false,
        hard: false,
        weight: 30,
        groupKey: 'g',
      },
    ])
  })
})
