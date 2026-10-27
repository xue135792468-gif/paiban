import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/api/http', () => ({ http: httpMock }))

import { useConstraintWorkspace } from './useConstraintWorkspace'

describe('useConstraintWorkspace', () => {
  beforeEach(() => {
    httpMock.get.mockReset()
    httpMock.put.mockReset()
    httpMock.delete.mockReset()
  })

  it('没有未保存内容时返回空状态', async () => {
    httpMock.get.mockResolvedValue({
      data: {
        exists: false,
        revision: 0,
        baseVersion: 0,
        payload: {},
        updatedAt: null,
        errors: [],
        invalidReferences: [],
      },
    })
    const workspace = useConstraintWorkspace({ semesterId: ref(3) })
    await expect(workspace.load()).resolves.toBeNull()
    expect(workspace.revision.value).toBe(0)
  })

  it('暂存带上本设备修订，成功后续用服务端修订', async () => {
    httpMock.get.mockResolvedValue({
      data: {
        exists: true,
        revision: 4,
        baseVersion: 2,
        payload: { text: '原文' },
        updatedAt: '2026-10-27T10:00:00Z',
        errors: [],
        invalidReferences: [],
      },
    })
    httpMock.put.mockResolvedValue({
      data: { exists: true, revision: 5, baseVersion: 2, payload: {}, updatedAt: null, errors: [], invalidReferences: [] },
    })
    const workspace = useConstraintWorkspace({ semesterId: ref(3) })
    await workspace.load()
    expect(workspace.revision.value).toBe(4)

    const result = await workspace.save({ text: '改过' }, 2)
    expect(result).toBe('SAVED')
    expect(httpMock.put).toHaveBeenCalledWith(
      '/constraint-workspace',
      { baseRevision: 4, baseVersion: 2, payload: { text: '改过' } },
      { params: { semesterId: 3 } },
    )
    expect(workspace.revision.value).toBe(5)
  })

  it('另一台设备先暂存时保留本地内容并给出服务端副本', async () => {
    httpMock.put.mockRejectedValue({
      response: {
        status: 409,
        data: { workspace: { exists: true, revision: 9, payload: { text: '另一台' } } },
      },
    })
    const workspace = useConstraintWorkspace({ semesterId: ref(3) })
    const result = await workspace.save({ text: '本地' }, 1)

    expect(result).toBe('CONFLICT')
    expect(workspace.conflict.value?.revision).toBe(9)
    expect(workspace.conflict.value?.payload.text).toBe('另一台')
    workspace.adoptServerRevision()
    expect(workspace.conflict.value).toBeNull()
    expect(workspace.revision.value).toBe(9)
  })

  it('暂存失败明确报错且不假装已跨设备保存', async () => {
    httpMock.put.mockRejectedValue({
      response: { status: 500, data: { message: '服务器内部错误' } },
    })
    const workspace = useConstraintWorkspace({ semesterId: ref(3) })
    await expect(workspace.save({ text: '本地' }, 1)).resolves.toBe('FAILED')
    expect(workspace.error.value).toBeTruthy()
  })

  it('放弃后修订归零', async () => {
    httpMock.delete.mockResolvedValue({})
    const workspace = useConstraintWorkspace({ semesterId: ref(3) })
    workspace.revision.value = 7
    await workspace.discard()
    expect(httpMock.delete).toHaveBeenCalledWith('/constraint-workspace', {
      params: { semesterId: 3 },
    })
    expect(workspace.revision.value).toBe(0)
  })
})
