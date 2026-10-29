import type { Entry } from '../types'

/**
 * 票 01：步骤 1 课卡拖拽的目标判定（前端唯一规则源）。
 *
 * 拖拽只表达「想和哪节课对调」的意图，松手后仍走步骤 2 的表单路径，
 * 由后端 swap-targets / swap-plans 最终裁决；无效投放不产生任何请求或写入。
 */
export interface DropVerdict {
  ok: boolean
  reason?: string
}

export function evaluateLessonDrop(
  source: Entry,
  target: Entry | null,
  targetDate: string,
  lessonDate: string,
): DropVerdict {
  if (!target) {
    return { ok: false, reason: '空格：空格只挪不补，不是有效调课目标' }
  }
  if (target.id === source.id) {
    return { ok: false, reason: '来源课本身不能作为调课目标' }
  }
  if (targetDate < lessonDate) {
    return {
      ok: false,
      reason: '窗口外：调课窗口从来源日期向后展开，不支持向来源日期之前搜索',
    }
  }
  if (target.className !== source.className) {
    return {
      ok: false,
      reason: `跨班：目标格是${target.className || '其他班级'}的课，不是同一班，不能对调`,
    }
  }
  return { ok: true }
}
