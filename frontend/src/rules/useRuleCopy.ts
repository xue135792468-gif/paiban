import type { Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import { copyRules, type CopyRulesResult } from '@/api/rules'

interface RuleCopyOptions {
  semesterId: Ref<number | null>
  baseVersion: Ref<number>
  setConflict: (state: { message: string; version: number }) => void
  reload: () => Promise<void>
}

/**
 * Ticket 08: the page-side half of the copy flow. It keeps the HTTP call and the
 * conflict bookkeeping out of the view, while the dialog component owns the visible
 * confirmation and result UI.
 */
export function useRuleCopy(options: RuleCopyOptions) {
  async function copyFromSemester(sourceSemesterId: number): Promise<CopyRulesResult | null> {
    const targetSemesterId = options.semesterId.value
    if (targetSemesterId === null) {
      return null
    }
    try {
      return await copyRules({
        sourceSemesterId,
        targetSemesterId,
        baseVersion: options.baseVersion.value,
      })
    } catch (e: any) {
      if (e?.response?.status === 409) {
        const body = e.response.data
        options.setConflict({
          message: serverMessage(e, '目标学期规则已被修改，请刷新页面后重试'),
          version: typeof body?.version === 'number' ? body.version : options.baseVersion.value,
        })
      }
      // The dialog shows the server's message; the page only records a 409 banner.
      throw e
    }
  }

  async function onCopied(result: CopyRulesResult): Promise<void> {
    await options.reload()
    ElMessage.success(`已复制 ${result.copiedCards} 张规则卡`)
    if (result.disabledCards > 0) {
      ElMessage.warning(`有 ${result.disabledCards} 张规则本学期用不上，见下方`)
    }
  }

  return { copyFromSemester, onCopied }
}
