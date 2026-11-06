import { beforeEach, describe, expect, it, vi } from 'vitest'

const postMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({ http: { post: postMock } }))

import { copyRules } from './rules'

describe('copyRules', () => {
  beforeEach(() => {
    postMock.mockReset()
  })

  it('posts the source, target and base version to the copy endpoint', async () => {
    const result = { copiedCards: 2, disabledCards: 1, issues: [] }
    postMock.mockResolvedValue({ data: result })

    const copied = await copyRules({
      sourceSemesterId: 3,
      targetSemesterId: 7,
      baseVersion: 11,
    })

    expect(postMock).toHaveBeenCalledWith('/constraints/copy-rules', {
      sourceSemesterId: 3,
      targetSemesterId: 7,
      baseVersion: 11,
    })
    expect(copied).toEqual(result)
  })
})
