import { describe, expect, it } from 'vitest'
import { isPastDate, today } from './types'

describe('types date utils', () => {
  it('today 返回本地日期分量拼出的 YYYY-MM-DD', () => {
    const now = new Date()
    const expected = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    expect(today()).toBe(expected)
  })

  it('isPastDate 覆盖昨天 true / 今天 false / 明天 false', () => {
    const now = new Date()

    const yesterdayDate = new Date(now)
    yesterdayDate.setDate(now.getDate() - 1)
    const yesterday = `${yesterdayDate.getFullYear()}-${String(yesterdayDate.getMonth() + 1).padStart(2, '0')}-${String(yesterdayDate.getDate()).padStart(2, '0')}`

    const todayStr = today()

    const tomorrowDate = new Date(now)
    tomorrowDate.setDate(now.getDate() + 1)
    const tomorrow = `${tomorrowDate.getFullYear()}-${String(tomorrowDate.getMonth() + 1).padStart(2, '0')}-${String(tomorrowDate.getDate()).padStart(2, '0')}`

    expect(isPastDate(yesterday)).toBe(true)
    expect(isPastDate(todayStr)).toBe(false)
    expect(isPastDate(tomorrow)).toBe(false)
  })

  it('isPastDate 处理空值返回 false', () => {
    expect(isPastDate('')).toBe(false)
  })
})
