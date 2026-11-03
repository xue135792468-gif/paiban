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
  suggestedRule: SuggestedRule | null
  recipeMatch: RecipeMatch
  warnings: string[]
  fingerprint: ManualFingerprint
  layout: 'LONG' | 'GRID'
}

export interface ManualCommitOptions {
  semesterId?: number | null
  draftName?: string
  teacherNameMappings?: Record<string, string>
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

export interface ImportRecipe {
  id: number
  name: string
  layout: 'LONG' | 'GRID'
  fingerprint: ManualFingerprint
  columns: ManualMapping
  options: Record<string, unknown>
  expected: Record<string, number>
  sample: unknown
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
  sample: unknown
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

export async function listImportRecipes(): Promise<ImportRecipe[]> {
  const { data } = await http.get<ImportRecipe[]>('/import-recipes')
  return data
}

export async function saveImportRecipe(payload: SaveRecipePayload): Promise<ImportRecipe> {
  const { data } = await http.post<ImportRecipe>('/import-recipes', payload)
  return data
}
