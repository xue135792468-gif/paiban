import { describe, expect, it } from 'vitest'
import { isUserIncluded } from './integration'

describe('isUserIncluded', () => {
  const shared = { userId: 'dd-1', departments: ['全校教职工', '食堂'] }

  it('keeps a multi-department user when only one of their departments is excluded', () => {
    expect(isUserIncluded(shared, ['食堂'], [])).toBe(true)
  })

  it('excludes a user only when every department they belong to is excluded', () => {
    expect(isUserIncluded({ userId: 'dd-2', departments: ['食堂'] }, ['食堂'], [])).toBe(false)
    expect(isUserIncluded(shared, ['全校教职工', '食堂'], [])).toBe(false)
  })

  it('excludes an explicitly excluded user', () => {
    expect(isUserIncluded(shared, [], ['dd-1'])).toBe(false)
  })
})
