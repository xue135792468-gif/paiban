import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { RuleImpact } from '@/api/rules'
import type { ConstraintConfigRequest } from '@/components/constraints/types'
import type { RuleCard } from '@/rules/compiler'

export interface RuleDeleteDeps {
  /** Saved type rows; their id is what the delete endpoint needs. */
  configs: Array<{ id?: number; type: string }>
  /** Unsaved edits: deleting a saved card would discard them, so it is refused. */
  dirty: boolean
  previewImpact: (payload: ConstraintConfigRequest[], title: string) => Promise<RuleImpact | null>
  impactError: string | null
  hideImpact: () => void
  reload: () => Promise<void>
  removeLocally: (card: RuleCard) => void
}

/**
 * Ticket 04: deleting a saved rule card goes through the server so it lands in the
 * 7-day recycle bin (one card, not the whole type). The impact preview is shown in
 * the same confirmation. A card that was never saved is only dropped from the page.
 */
export async function deleteRuleCardWithConfirmation(
  card: RuleCard,
  payload: ConstraintConfigRequest[],
  deps: RuleDeleteDeps,
): Promise<void> {
  const configId = configIdOf(card, deps.configs)
  const recover = configId === null
    ? '这条规则还没有保存。'
    : '删除后可在「最近删除」中恢复（7 天内）。'
  let text = `删除这张规则卡？${recover}`
  const result = await deps.previewImpact(payload, '删除前的影响')
  if (result) {
    text = `这条规则影响 ${result.classes.length} 个班 / ${result.teachers.length} 位教师 / `
      + `${result.lessonCount} 个课节，${result.conflicts.length} 条冲突。${recover}`
    if (result.errors.length > 0) {
      text += `\n配置还有错误：${result.errors.join('；')}`
    }
  } else if (deps.impactError) {
    text = `无法获取影响预览（${deps.impactError}）。仍要删除这张规则卡吗？${recover}`
  }
  try {
    await ElMessageBox.confirm(text, '删除规则', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  if (configId === null) {
    deps.removeLocally(card)
    deps.hideImpact()
    return
  }
  if (deps.dirty) {
    ElMessage.warning('还有未保存的修改：请先保存，再删除规则卡')
    return
  }
  try {
    await http.delete(`/rules/${configId}`, { params: { cardKey: card.cardId } })
    ElMessage.success('规则已删除，可在「最近删除」中恢复')
    deps.hideImpact()
    await deps.reload()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '删除规则失败'))
  }
}

/** The saved type row that owns a card; null when the card was never saved. */
function configIdOf(
  card: RuleCard,
  configs: Array<{ id?: number; type: string }>,
): number | null {
  const type = card.sentences[0]?.type
  if (!type) {
    return null
  }
  return configs.find((config) => config.type === type)?.id ?? null
}
