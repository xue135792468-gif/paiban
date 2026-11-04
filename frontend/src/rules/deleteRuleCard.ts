import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { RuleImpact } from '@/api/rules'
import type { ConstraintConfigRequest } from '@/components/constraints/types'
import type { RuleCard } from '@/rules/compiler'

export interface RuleDeleteDeps {
  /** Saved type rows; their id is what the delete endpoint needs. */
  configs: Array<{ id?: number; type: string }>
  /** The whole page has unsaved edits (any card, not only this one). */
  dirty: boolean
  /** True when this card itself differs from its saved version. */
  cardChanged: boolean
  /** Readable sentence of the card, so the messages name it. */
  cardLabel: string
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
 *
 * <p>Unsaved edits are checked before the confirmation, not after: only this card's
 * own edits block the delete. Edits elsewhere on the page are named in the dialog so
 * the user knows they will be discarded by the reload.
 */
export async function deleteRuleCardWithConfirmation(
  card: RuleCard,
  payload: ConstraintConfigRequest[],
  deps: RuleDeleteDeps,
): Promise<void> {
  const configId = configIdOf(card, deps.configs)
  if (configId !== null && deps.cardChanged) {
    ElMessage.warning(`「${deps.cardLabel}」还有未保存的修改：请先保存或撤销，再删除这张规则卡`)
    return
  }
  const name = deps.cardLabel ? `「${deps.cardLabel}」` : '这张规则卡'
  const recover = configId === null
    ? '这条规则还没有保存。'
    : '删除后可在「最近删除」中恢复（7 天内）。'
  const otherEdits = configId !== null && deps.dirty
    ? '\n页面其他规则还有未保存的修改，删除并刷新后会丢弃这些修改。'
    : ''
  let text = `删除${name}？${recover}${otherEdits}`
  const result = await deps.previewImpact(payload, '删除前的影响')
  if (result) {
    text = `这条规则影响 ${result.classes.length} 个班 / ${result.teachers.length} 位教师 / `
      + `${result.lessonCount} 个课节，${result.conflicts.length} 条冲突。${recover}${otherEdits}`
    if (result.errors.length > 0) {
      text += `\n配置还有错误：${result.errors.join('；')}`
    }
  } else if (deps.impactError) {
    text = `无法获取影响预览（${deps.impactError}）。仍要删除${name}吗？${recover}${otherEdits}`
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
  const entryIds = entryIdsOf(card)
  try {
    await http.delete(`/rules/${configId}`, {
      // entryIds pins the exact rows of this card, so a card read back from a legacy
      // row without a group key deletes correctly too.
      params: { cardKey: card.cardId, ...(entryIds.length > 0 ? { entryIds: entryIds.join(',') } : {}) },
    })
    ElMessage.success('规则已删除，可在「最近删除」中恢复')
    deps.hideImpact()
    await deps.reload()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '删除规则失败'))
  }
}

/** The stored row ids this card is made of. */
function entryIdsOf(card: RuleCard): number[] {
  const ids = new Set<number>()
  card.sentences.forEach((sentence) => {
    Object.values(sentence.entryIds).forEach((id) => {
      if (typeof id === 'number') {
        ids.add(id)
      }
    })
  })
  return Array.from(ids)
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
