import { describe, expect, it } from 'vitest'
import { activeOnly } from './activeOptions'

describe('activeOnly', () => {
  it('drops explicitly disabled rows and keeps active or unknown ones', () => {
    const items = [
      { id: 1, active: true },
      { id: 2, active: false },
      { id: 3 },
      { id: 4, active: null },
    ]

    expect(activeOnly(items).map((item) => item.id)).toEqual([1, 3, 4])
  })

  it('tolerates missing lists', () => {
    expect(activeOnly(undefined)).toEqual([])
    expect(activeOnly(null)).toEqual([])
  })
})
