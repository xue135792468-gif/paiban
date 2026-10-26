import { computed, ref, type Ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { applyResultOf, type ApplyResult } from './templateModel'
import type {
  ConstraintConfigDto,
  TemplateApplyPreview,
  TemplateItem,
  TemplateReferenceIssue,
} from './types'

/**
 * The template half of the constraint page: the saved list, the dry run, the apply
 * itself and the report the page shows afterwards.
 *
 * The apply never guesses: the body only carries the class mapping the user picked, so
 * an unrebound class comes back as a refusal with the offending rows instead of being
 * matched to a same-named class of another semester.
 */
export function useConstraintTemplates(options: {
  semesterId: Ref<number | null>
  reload: () => Promise<void>
}) {
  const templates = ref<TemplateItem[]>([])
  const selectedId = ref<number | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const applying = ref(false)
  const previewing = ref(false)
  const deleting = ref(false)
  /** What the last successful apply restored, straight from the response. */
  const result = ref<{ name: string; counts: ApplyResult } | null>(null)
  /** The refusal message of the last apply/preview, if any. */
  const errorMessage = ref<string | null>(null)
  /** References of the last preview/refusal that the target semester cannot take. */
  const issues = ref<TemplateReferenceIssue[]>([])
  const preview = ref<TemplateApplyPreview | null>(null)

  const selected = computed(
    () => templates.value.find((item) => item.id === selectedId.value) ?? null,
  )

  async function load() {
    loading.value = true
    try {
      const { data } = await http.get<TemplateItem[]>('/constraint-templates')
      templates.value = data ?? []
    } catch (e: any) {
      errorMessage.value = serverMessage(e, '加载约束模板失败')
    } finally {
      loading.value = false
    }
  }

  /** Saves the current semester as a template; the server owns the snapshot. */
  async function saveAs(name: string): Promise<boolean> {
    if (!options.semesterId.value) {
      return false
    }
    saving.value = true
    errorMessage.value = null
    try {
      await http.post(
        '/constraint-templates',
        { name },
        { params: { semesterId: options.semesterId.value } },
      )
      await load()
      return true
    } catch (e: any) {
      errorMessage.value = serverMessage(e, '保存模板失败')
      return false
    } finally {
      saving.value = false
    }
  }

  /** Dry run: what the apply would restore and what stands in its way. */
  async function runPreview(classMapping: Record<string, number> = {}) {
    if (!options.semesterId.value || !selectedId.value) {
      return null
    }
    previewing.value = true
    errorMessage.value = null
    try {
      const { data } = await http.post<TemplateApplyPreview>(
        `/constraint-templates/${selectedId.value}/preview`,
        { classMapping },
        { params: { semesterId: options.semesterId.value } },
      )
      preview.value = data ?? null
      issues.value = data?.invalidReferences ?? []
      return preview.value
    } catch (e: any) {
      errorMessage.value = serverMessage(e, '预览模板套用结果失败')
      return null
    } finally {
      previewing.value = false
    }
  }

  /**
   * Applies the template. A refusal keeps the current configuration untouched (the
   * server validates before it deletes anything) and fills the issue list, so the page
   * can let the user pick replacement classes and retry.
   */
  async function apply(classMapping: Record<string, number> = {}): Promise<ApplyResult | null> {
    if (!options.semesterId.value || !selectedId.value) {
      return null
    }
    const name = selected.value?.name ?? '所选模板'
    applying.value = true
    errorMessage.value = null
    try {
      const { data } = await http.post<ConstraintConfigDto[]>(
        `/constraint-templates/${selectedId.value}/apply`,
        { classMapping },
        { params: { semesterId: options.semesterId.value } },
      )
      const counts = applyResultOf(data)
      result.value = { name, counts }
      issues.value = []
      await options.reload()
      return counts
    } catch (e: any) {
      const refused = refusalIssues(e)
      if (refused.length > 0) {
        issues.value = refused
      }
      errorMessage.value = serverMessage(e, '套用模板失败')
      return null
    } finally {
      applying.value = false
    }
  }

  async function remove(): Promise<boolean> {
    if (!selectedId.value) {
      return false
    }
    deleting.value = true
    errorMessage.value = null
    try {
      await http.delete(`/constraint-templates/${selectedId.value}`)
      selectedId.value = null
      result.value = null
      await load()
      return true
    } catch (e: any) {
      errorMessage.value = serverMessage(e, '删除模板失败')
      return false
    } finally {
      deleting.value = false
    }
  }

  /** Drops the report of the previous apply, e.g. when the semester changes. */
  function clearReport() {
    result.value = null
    errorMessage.value = null
    issues.value = []
    preview.value = null
  }

  return {
    templates,
    selectedId,
    selected,
    loading,
    saving,
    applying,
    previewing,
    deleting,
    result,
    errorMessage,
    issues,
    preview,
    load,
    saveAs,
    runPreview,
    apply,
    remove,
    clearReport,
  }
}

/** The structured refusal list of a 409 apply, when the body carries one. */
function refusalIssues(error: unknown): TemplateReferenceIssue[] {
  const body = (error as { response?: { data?: { invalidReferences?: unknown } } })?.response?.data
  const rows = body?.invalidReferences
  return Array.isArray(rows) ? (rows as TemplateReferenceIssue[]) : []
}
