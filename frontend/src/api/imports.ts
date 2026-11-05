import { http } from '@/api/http'

/**
 * Transport shapes for the manual (non-AI) import and for import recipes.
 *
 * This module only holds paths and payload shapes so the page and its specs agree on
 * the contract; it deliberately does not import anything from the AI import composables.
 * The endpoints are independent of `/api/ai/*`, so the page works with no AI provider.
 */

/** Column indexes are 0-based; headerRow -1 means "no header row". */
export interface ManualMapping {
  sheetName?: string
  headerRow?: number
  delimiter?: string
  keyColumn?: number | null
  keyParts?: string[]
  classNameColumn?: number | null
  dayOfWeekColumn?: number | null
  periodNoColumn?: number | null
  courseColumn?: number | null
  teacherColumn?: number | null
  ignoreBlankRows?: boolean
}

export interface ManualSheet {
  name: string
  rowCount: number
  columnCount: number
  headerRow: number
  headerless: boolean
  headers: string[]
  sampleRows: string[][]
  suggestedMapping: ManualMapping
}

export interface ManualInspect {
  sheets: ManualSheet[]
}

export type RecipeDifferenceKind = 'ADDED' | 'REMOVED' | 'RENAMED'

export interface RecipeDifference {
  kind: RecipeDifferenceKind
  column: string
  detail: string
}

export type RecipeMatchKind = 'EXACT' | 'SIMILAR' | 'NONE'

export interface RecipeMatch {
  kind: RecipeMatchKind
  recipeId?: number
  recipeName?: string
  differences: RecipeDifference[]
  /** Counts the recipe promised when it was saved; the preview uses them to warn. */
  expected?: Record<string, number>
  mapping?: ManualMapping
}

export interface TeacherWarningReason {
  code: string
  message: string
}

export interface TeacherWarning {
  value: string
  reasons: TeacherWarningReason[]
  lessonCount: number
  rowNumbers: number[]
}

export interface SuggestedRule {
  type: 'CLASS_UNAVAILABLE'
  dayOfWeek: number
  periodNo: number
  text: string
}

export interface ManualPreviewRow {
  row: number
  className: string
  dayOfWeek: number
  periodNo: number
  courseName: string
  teacherName: string
  empty: boolean
}

export interface ManualFingerprint {
  sheetName: string
  headerRow: number
  columnCount: number
  headers: string[]
  delimiter: string
  layout: 'LONG' | 'GRID'
}

export interface ManualPreview {
  slotCount: number
  lessonCount: number
  emptySlotCount: number
  teacherCount: number
  courseCount: number
  rows: ManualPreviewRow[]
  truncated: boolean
  teacherWarnings: TeacherWarning[]
  /** 占位名里“系统里还没有同名教师”的那些：入库会新建，必须先选择怎么处理。 */
  pendingTeachers: string[]
  suggestedRule: SuggestedRule | null
  recipeMatch: RecipeMatch
  warnings: string[]
  fingerprint: ManualFingerprint
  layout: 'LONG' | 'GRID'
}

export interface ManualCommitOptions {
  semesterId?: number | null
  draftName?: string
  /** 占位教师 → 教师 id（或另一个名字）；没映射的占位教师必须靠下面的开关明确保留。 */
  teacherNameMappings?: Record<string, string>
  /** 明确“保留占位名并创建教师”。不置位又没有映射时，服务端 400 并返回待处理名单。 */
  keepPlaceholderTeachers?: boolean
  applySuggestedRule?: boolean
}

export interface RowError {
  row: number
  field: string
  message: string
  candidates: string[]
}

export interface ManualCommitResult {
  draftId: number | null
  imported: number
  skippedEmpty: number
  errors: RowError[]
  warnings: string[]
  appliedRecipeId: number | null
  appliedRule: SuggestedRule | null
}

export interface RecipeSample {
  path?: string
  fileName?: string
  size?: number
  sha256?: string
}

export interface ImportRecipe {
  id: number
  name: string
  layout: 'LONG' | 'GRID'
  fingerprint: ManualFingerprint
  columns: ManualMapping
  options: Record<string, unknown>
  expected: Record<string, number>
  sample?: RecipeSample | null
  version: number
  enabled: boolean
  createdBy: string
  createdAt: string
  lastUsedAt: string | null
}

export interface SaveRecipePayload {
  name: string
  layout: 'LONG' | 'GRID'
  fingerprint: ManualFingerprint
  columns: ManualMapping
  options: Record<string, unknown>
  expected: Record<string, number>
  /** Ticket 07 provenance: set only when the user adopted an AI suggestion. */
  aiSuggested?: boolean
  aiRawResponse?: string
  aiSuggestedMapping?: ManualMapping
}

/** Column mapping the model proposed; null/absent columns mean "no such column". */
export interface AiRecipeSuggestion {
  layout: 'LONG' | 'GRID'
  sheetName?: string
  headerRow?: number
  delimiter?: string
  compositeColumn?: number | null
  classColumn?: number | null
  dayColumn?: number | null
  periodColumn?: number | null
  courseColumn?: number | null
  teacherColumn?: number | null
  ignoreColumns?: number[]
  reasoning?: string
}

/**
 * `available: false` is a normal answer when no AI provider is configured. A failed
 * call is `available: true, success: false` with `raw` and `error`, so the page can
 * keep the manual form untouched.
 */
export interface AiRecipeSuggestionResult {
  available: boolean
  success: boolean
  suggestion?: AiRecipeSuggestion
  previewSummary?: RecipeCounts
  raw?: string
  explanation?: string
  modelName?: string
  latencyMs?: number
  error?: string
}

export interface RecipePatchPayload {
  name?: string
  enabled?: boolean
  layout?: 'LONG' | 'GRID'
  columns?: ManualMapping
  options?: Record<string, unknown>
  expected?: Record<string, number>
}

export interface RecipeCounts {
  slotCount: number
  lessonCount: number
  emptySlotCount: number
  teacherCount: number
  courseCount: number
}

export interface RecipeCountDifference {
  field: keyof RecipeCounts | string
  label: string
  expected: number | null
  actual: number | null
}

export interface RecipeTestResult {
  pass: boolean
  actual: RecipeCounts
  expected: Record<string, number>
  differences: RecipeCountDifference[]
}

export interface RecipeVersion {
  version: number
  createdAt: string
  createdBy: string
  summary: string
}

/** One count that drifted far enough from the recipe's expectation to warn. */
export interface RecipeCountDrift {
  field: string
  label: string
  expected: number
  actual: number
  message: string
}

function fileForm(file: File): FormData {
  const form = new FormData()
  form.append('file', file)
  return form
}

export async function inspectManualImport(file: File): Promise<ManualInspect> {
  const { data } = await http.post<ManualInspect>('/imports/manual/inspect', fileForm(file))
  return data
}

export async function previewManualImport(
  file: File,
  mapping: ManualMapping,
  semesterId?: number | null,
): Promise<ManualPreview> {
  const form = fileForm(file)
  form.append('mapping', JSON.stringify(mapping ?? {}))
  const config = semesterId ? { params: { semesterId } } : undefined
  const { data } = await http.post<ManualPreview>('/imports/manual/preview', form, config)
  return data
}

export async function commitManualImport(
  file: File,
  mapping: ManualMapping,
  options: ManualCommitOptions,
): Promise<ManualCommitResult> {
  const form = fileForm(file)
  form.append('mapping', JSON.stringify(mapping ?? {}))
  form.append('options', JSON.stringify(options ?? {}))
  const { data } = await http.post<ManualCommitResult>('/imports/manual/commit', form)
  return data
}

export async function matchImportRecipe(
  file: File,
  mapping?: ManualMapping,
): Promise<RecipeMatch> {
  const form = fileForm(file)
  if (mapping && Object.keys(mapping).length) {
    form.append('mapping', JSON.stringify(mapping))
  }
  const { data } = await http.post<RecipeMatch>('/import-recipes/match', form)
  return data
}

/**
 * Asks the model for a column mapping when no recipe matched. The server re-parses
 * the proposal with the manual preview pipeline before answering, so a `success`
 * result is already known to produce rows.
 */
export async function suggestImportColumns(
  file: File,
  sheetName?: string | null,
  headerRow?: number | null,
): Promise<AiRecipeSuggestionResult> {
  const form = fileForm(file)
  if (sheetName) {
    form.append('sheetName', sheetName)
  }
  if (headerRow !== null && headerRow !== undefined) {
    form.append('headerRow', String(headerRow))
  }
  const { data } = await http.post<AiRecipeSuggestionResult>(
    '/imports/manual/ai-suggest',
    form,
  )
  return data
}

/**
 * Turns an AI proposal into the same mapping the manual selects hold. Composite
 * columns keep the 班级|星期|节次 order the server validated; everything else is
 * copied verbatim so the user can still edit any field before previewing.
 */
export function mappingFromSuggestion(suggestion: AiRecipeSuggestion): ManualMapping {
  const mapping: ManualMapping = {
    sheetName: suggestion.sheetName,
    headerRow: suggestion.headerRow ?? -1,
    ignoreBlankRows: true,
    keyParts: ['className', 'dayOfWeek', 'periodNo'],
  }
  if (suggestion.compositeColumn !== null && suggestion.compositeColumn !== undefined) {
    mapping.keyColumn = suggestion.compositeColumn
    mapping.delimiter = suggestion.delimiter || '|'
  } else {
    mapping.keyColumn = null
    mapping.classNameColumn = suggestion.classColumn ?? null
    mapping.dayOfWeekColumn = suggestion.dayColumn ?? null
    mapping.periodNoColumn = suggestion.periodColumn ?? null
  }
  mapping.courseColumn = suggestion.courseColumn ?? null
  mapping.teacherColumn = suggestion.teacherColumn ?? null
  return mapping
}

export async function listImportRecipes(): Promise<ImportRecipe[]> {
  const { data } = await http.get<ImportRecipe[]>('/import-recipes')
  return data
}

export async function getImportRecipe(id: number): Promise<ImportRecipe> {
  const { data } = await http.get<ImportRecipe>(`/import-recipes/${id}`)
  return data
}

/** Save re-runs the sample server-side; a 409 means the counts did not match. */
export async function saveImportRecipe(
  payload: SaveRecipePayload,
  sampleFile?: File | null,
): Promise<ImportRecipe> {
  const form = new FormData()
  form.append('request', JSON.stringify(payload))
  if (sampleFile) {
    form.append('sampleFile', sampleFile)
  }
  const { data } = await http.post<ImportRecipe>('/import-recipes', form)
  return data
}

export async function updateImportRecipe(
  id: number,
  payload: RecipePatchPayload,
  sampleFile?: File | null,
): Promise<ImportRecipe> {
  const form = new FormData()
  form.append('request', JSON.stringify(payload))
  if (sampleFile) {
    form.append('sampleFile', sampleFile)
  }
  const { data } = await http.patch<ImportRecipe>(`/import-recipes/${id}`, form)
  return data
}

export async function testImportRecipe(id: number): Promise<RecipeTestResult> {
  const { data } = await http.post<RecipeTestResult>(`/import-recipes/${id}/test`)
  return data
}

export async function listImportRecipeVersions(id: number): Promise<RecipeVersion[]> {
  const { data } = await http.get<RecipeVersion[]>(`/import-recipes/${id}/versions`)
  return data
}

export async function rollbackImportRecipe(id: number, version: number): Promise<ImportRecipe> {
  const { data } = await http.post<ImportRecipe>(`/import-recipes/${id}/rollback/${version}`)
  return data
}

export async function deleteImportRecipe(
  id: number,
): Promise<{ batchId: string; deletedCount: number }> {
  const { data } = await http.delete<{ batchId: string; deletedCount: number }>(
    `/import-recipes/${id}`,
  )
  return data
}

/**
 * “结果差异过大” heuristic for the import preview. The recipe's saved counts are the
 * baseline; empty slots tolerate 50% drift, lesson counts 10%. Pure so the component
 * stays presentational and the rule has one home.
 */
export function recipeCountDrift(
  actual: Pick<RecipeCounts, 'slotCount' | 'lessonCount' | 'emptySlotCount'>,
  expected?: Record<string, number> | null,
): RecipeCountDrift[] {
  if (!expected) return []
  const drifts: RecipeCountDrift[] = []
  const empty = expected.emptySlotCount
  if (typeof empty === 'number' && Math.abs(actual.emptySlotCount - empty) > empty * 0.5) {
    drifts.push({
      field: 'emptySlotCount',
      label: '空槽数',
      expected: empty,
      actual: actual.emptySlotCount,
      message: `空槽数与配方预期差异过大：预期 ${empty}，本次 ${actual.emptySlotCount}`,
    })
  }
  const lessons = expected.lessonCount
  if (
    typeof lessons === 'number' &&
    lessons > 0 &&
    Math.abs(actual.lessonCount - lessons) > lessons * 0.1
  ) {
    drifts.push({
      field: 'lessonCount',
      label: '有课数',
      expected: lessons,
      actual: actual.lessonCount,
      message: `有课数与配方预期差异过大：预期 ${lessons}，本次 ${actual.lessonCount}`,
    })
  }
  return drifts
}
