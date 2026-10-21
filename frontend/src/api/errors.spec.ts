import { describe, expect, it } from 'vitest'
import { aiParseErrorMessage, serverMessage } from './errors'

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

describe('aiParseErrorMessage', () => {
  it('AI 解析超时给出可操作提示', () => {
    expect(aiParseErrorMessage({ code: 'ECONNABORTED' })).toBe('AI 解析超时，请重试或拆分文件')
    expect(aiParseErrorMessage({ code: 'ETIMEDOUT' })).toBe('AI 解析超时，请重试或拆分文件')
  })

  it('网络错误没有响应时也按超时处理', () => {
    expect(aiParseErrorMessage(new Error('Network Error'))).toBe('AI 解析超时，请重试或拆分文件')
    expect(aiParseErrorMessage({ code: 'ERR_NETWORK' })).toBe('AI 解析超时，请重试或拆分文件')
  })

  it('服务端返回了 message 时优先显示', () => {
    expect(
      aiParseErrorMessage({
        code: 'ECONNABORTED',
        response: { data: { message: 'AI 解析失败：请检查 AI 供应商配置' } },
      }),
    ).toBe('AI 解析失败：请检查 AI 供应商配置')
  })

  it('其他失败保留通用提示', () => {
    expect(aiParseErrorMessage({ response: { status: 500, data: {} } })).toBe('操作失败，请稍后重试')
    expect(aiParseErrorMessage(null)).toBe('AI 解析超时，请重试或拆分文件')
  })
})
