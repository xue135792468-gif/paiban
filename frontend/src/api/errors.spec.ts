import { describe, expect, it } from 'vitest'
import { serverMessage } from './errors'

describe('serverMessage', () => {
  it('prefers the message the API sent', () => {
    expect(serverMessage({ response: { data: { message: '课表已发布，发布后请走调代流程' } } }, '失败'))
      .toBe('课表已发布，发布后请走调代流程')
  })

  it('falls back when the body carries no message', () => {
    expect(serverMessage(new Error('network down'), '移动失败')).toBe('移动失败')
    expect(serverMessage({ response: { data: {} } }, '移动失败')).toBe('移动失败')
    expect(serverMessage(null, '移动失败')).toBe('移动失败')
    expect(serverMessage(undefined, '移动失败')).toBe('移动失败')
  })
})
