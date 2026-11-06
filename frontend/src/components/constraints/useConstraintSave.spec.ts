import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const httpMock = vi.hoisted(() => ({ put: vi.fn(), get: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const messageMock = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}))
const boxMock = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: messageMock, ElMessageBox: boxMock }))

import { useConstraintDraft } from './useConstraintDraft'
import { useConstraintSave } from './useConstraintSave'
import type { ConstraintConfigDto, ConstraintTypeDef, ParamDef } from './types'

const SLOT_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const CATALOG: ConstraintTypeDef[] = [
  {
    code: 'TEACHER_UNAVAILABLE',
    name: '教师禁排时段',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    description: '',
    defaultHard: true,
    defaultWeight: 100,
    params: SLOT_PARAMS,
  },
]

const CONFIGS: ConstraintConfigDto[] = [
  {
    type: 'TEACHER_UNAVAILABLE',
    params: {},
    weight: 100,
    hard: true,
    enabled: true,
    entries: [{ id: 11, params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } }],
  },
]

/**
 * 票 18 的核心契约：保存成功本身要确定性地清掉「有未保存的修改」，不能靠
 * `reload()` 里 hydrate 的副作用。这里的 `reload` 故意什么都不做（真实页面里它
 * 会再拉一次服务端配置并 hydrate），`onSaved` 也故意不清状态，于是这些测试只
 * 能靠 `useConstraintSave` 自己把草稿标成已保存。
 */
function setup() {
  const draft = useConstraintDraft()
  draft.hydrate(CATALOG, CONFIGS)
  const semesterId = ref<number | null>(1)
  const conflicts = ref<string[]>([])
  // 模拟「reload 没把配置拉回来 / 拉回来的和提交的不一致」：不做任何 hydrate。
  const reload = vi.fn(async () => {})
  const onSaved = vi.fn()
  const save = useConstraintSave({ draft, semesterId, conflicts, reload, onSaved })
  const key = draft.rows.value[0].entries[0].key
  // 制造一处真实的未保存改动。
  draft.updateSlot('TEACHER_UNAVAILABLE', key, draft.rows.value[0].entries[0].slots![0].key, 'periodNo', 6)
  return { draft, save, reload, onSaved }
}

describe('useConstraintSave', () => {
  beforeEach(() => {
    httpMock.put.mockReset()
    httpMock.get.mockReset()
    messageMock.success.mockReset()
    messageMock.error.mockReset()
    boxMock.confirm.mockReset()
  })

  it('保存成功后立即清掉未保存标记，不依赖 reload 里 hydrate 的副作用', async () => {
    const { draft, save, reload, onSaved } = setup()
    expect(draft.dirty.value).toBe(true)
    httpMock.put.mockResolvedValue({ status: 200, data: {} })

    await save.save()

    // 票 18：dirty 的清除必须由保存成功这一事实决定。当前实现只在 reload →
    // hydrate → markSaved 里间接对齐，reload 没 hydrate 时 dirty 会永远是 1。
    expect(draft.dirty.value).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(onSaved).toHaveBeenCalledTimes(1)
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('409 冲突时保留未保存标记，并给出可对比的最新配置', async () => {
    const { draft, save } = setup()
    expect(draft.dirty.value).toBe(true)
    httpMock.put.mockRejectedValue({
      response: {
        status: 409,
        data: {
          message: '约束配置已被他人修改，请核对后再保存',
          version: 7,
          configs: [
            {
              type: 'TEACHER_UNAVAILABLE',
              params: {},
              weight: 100,
              hard: true,
              enabled: true,
              entries: [{ id: 21, params: { teacherId: 2, dayOfWeek: 4, periodNo: 1 } }],
            },
          ],
        },
      },
    })

    await save.save()

    expect(save.conflict.value?.version).toBe(7)
    expect(save.conflict.value?.configs).toHaveLength(1)
    // 用户输入不丢：不保存成功就绝不把草稿标成已保存。
    expect(draft.dirty.value).toBe(true)
  })

  it('其它保存失败时保留未保存标记，并把服务端原因写进提示', async () => {
    const { draft, save } = setup()
    httpMock.put.mockRejectedValue({
      response: { status: 500, data: { message: '教师不存在' } },
    })

    await save.save()

    expect(save.saveMessage.value).toBe('教师不存在')
    expect(messageMock.error).toHaveBeenCalled()
    expect(draft.dirty.value).toBe(true)
  })
})
