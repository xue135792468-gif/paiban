import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ManualImportView from './ManualImportView.vue'
import { useAuthStore } from '@/stores/auth'
import type {
  AiRecipeSuggestionResult,
  ManualInspect,
  ManualPreview,
  RecipeMatch,
} from '@/api/imports'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const importsMock = vi.hoisted(() => ({
  inspectManualImport: vi.fn(),
  previewManualImport: vi.fn(),
  commitManualImport: vi.fn(),
  matchImportRecipe: vi.fn(),
  listImportRecipes: vi.fn(),
  saveImportRecipe: vi.fn(),
  suggestImportColumns: vi.fn(),
}))
vi.mock('@/api/imports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/imports')>()
  return { ...actual, ...importsMock }
})

const SHEET = {
  name: 'CSV',
  rowCount: 560,
  columnCount: 3,
  headerRow: 0,
  headerless: false,
  headers: ['班级|星期|节次', '课程', '教师'],
  sampleRows: [['八（1）|星期一|1', '语文', '张三']],
  suggestedMapping: {
    sheetName: 'CSV',
    headerRow: 0,
    delimiter: '|',
    keyColumn: 0,
    keyParts: ['className', 'dayOfWeek', 'periodNo'],
    courseColumn: 1,
    teacherColumn: 2,
    ignoreBlankRows: true,
  },
}

const INSPECT: ManualInspect = { sheets: [SHEET] }

const PREVIEW: ManualPreview = {
  slotCount: 560,
  lessonCount: 546,
  emptySlotCount: 14,
  teacherCount: 48,
  courseCount: 16,
  rows: [
    { row: 1, className: '八（1）', dayOfWeek: 1, periodNo: 1, courseName: '语文', teacherName: '张三', empty: false },
    { row: 2, className: '八（1）', dayOfWeek: 5, periodNo: 7, courseName: '', teacherName: '', empty: true },
  ],
  truncated: false,
  teacherWarnings: [
    { value: '信息技术', reasons: [{ code: 'placeholder', message: '命中占位词' }], lessonCount: 14, rowNumbers: [1] },
  ],
  suggestedRule: { type: 'CLASS_UNAVAILABLE', dayOfWeek: 5, periodNo: 7, text: '全校周五第 7 节不上课' },
  recipeMatch: { kind: 'NONE', differences: [] },
  warnings: [],
  fingerprint: {
    sheetName: 'CSV',
    headerRow: 0,
    columnCount: 3,
    headers: ['教师', '班级|星期|节次', '课程'],
    delimiter: '|',
    layout: 'LONG',
  },
  layout: 'LONG',
}

function exactMatch(): RecipeMatch {
  return {
    kind: 'EXACT',
    recipeId: 5,
    recipeName: '八年级长表',
    differences: [],
    mapping: SHEET.suggestedMapping,
  }
}

const AI_SUGGESTION: AiRecipeSuggestionResult = {
  available: true,
  success: true,
  suggestion: {
    layout: 'LONG',
    sheetName: 'CSV',
    headerRow: 0,
    delimiter: '|',
    compositeColumn: 0,
    courseColumn: 1,
    teacherColumn: 2,
    ignoreColumns: [],
    reasoning: '班级、星期、节次在复合列里，所以按复合键映射。',
  },
  previewSummary: {
    slotCount: 560,
    lessonCount: 546,
    emptySlotCount: 14,
    teacherCount: 48,
    courseCount: 16,
  },
  raw: '{"compositeColumn":0,"courseColumn":1,"teacherColumn":2}',
  modelName: 'stub-model',
}

/** The page reads `/ai/status` on mount; only `data_import` matters here. */
function httpGet(aiConfigured: boolean) {
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/ai/status') {
      return Promise.resolve({
        data: {
          configured: aiConfigured,
          tasks: { data_import: { configured: aiConfigured } },
        },
      })
    }
    if (url === '/semesters') {
      return Promise.resolve({ data: [{ id: 7, name: '2025 秋季学期', current: true }] })
    }
    if (url === '/teachers') {
      return Promise.resolve({ data: [{ id: 3, name: '张三' }, { id: 4, name: '李四' }] })
    }
    return Promise.resolve({ data: [] })
  })
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    roles: [],
    permissions: ['foundation:manage'],
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/data/import/manual', component: { template: '<div />' } },
      { path: '/data/import', component: { template: '<div />' } },
      { path: '/workbench/drafts/:id', name: 'draft-editor', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/data/import/manual')
  await router.isReady()
  const wrapper = mount(ManualImportView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

async function chooseFile(wrapper: VueWrapper) {
  const input = wrapper.get('[data-testid="manual-file"]')
  const file = new File(['班级|星期|节次,课程,教师\n八（1）|星期一|1,语文,张三'], '课表.csv', {
    type: 'text/csv',
  })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('手动导入页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpGet(false)
    importsMock.inspectManualImport.mockResolvedValue(INSPECT)
    importsMock.matchImportRecipe.mockResolvedValue({ kind: 'NONE', differences: [] })
    importsMock.previewManualImport.mockResolvedValue(PREVIEW)
    importsMock.commitManualImport.mockResolvedValue({
      draftId: 9,
      imported: 546,
      skippedEmpty: 14,
      errors: [],
      warnings: [],
      appliedRecipeId: null,
      appliedRule: PREVIEW.suggestedRule,
    })
    importsMock.saveImportRecipe.mockResolvedValue({ id: 11, name: '八年级长表' })
    importsMock.suggestImportColumns.mockResolvedValue(AI_SUGGESTION)
  })

  it('识别文件后给出列映射，预览展示空槽与占位教师统计', async () => {
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    expect(importsMock.inspectManualImport).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="manual-mapper"]').exists()).toBe(true)

    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="stat-slots"]').text()).toContain('560')
    expect(wrapper.get('[data-testid="stat-lessons"]').text()).toContain('546')
    expect(wrapper.get('[data-testid="stat-empty"]').text()).toContain('14')
    expect(wrapper.get('[data-testid="stat-teachers"]').text()).toContain('48')
    expect(wrapper.get('[data-testid="recipe-match"]').text()).toContain('未找到配方')
    expect(wrapper.get('[data-testid="suggested-rule"]').text()).toContain('全校周五第 7 节不上课')
    expect(wrapper.get('[data-testid="teacher-warning"]').text()).toContain('信息技术')
  })

  it('上传同格式文件时自动套用 EXACT 配方并直接预览', async () => {
    importsMock.matchImportRecipe.mockResolvedValue(exactMatch())
    importsMock.previewManualImport.mockResolvedValue({ ...PREVIEW, recipeMatch: exactMatch() })
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    expect(importsMock.previewManualImport).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="recipe-match"]').text()).toContain('已套用配方')
    expect(wrapper.get('[data-testid="recipe-match"]').text()).toContain('八年级长表')
  })

  it('入库成功后保存配方，带上当前映射与预期统计', async () => {
    const { wrapper } = await mountView()
    await chooseFile(wrapper)
    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="manual-commit"]').trigger('click')
    await flushPromises()
    expect(importsMock.commitManualImport).toHaveBeenCalledWith(
      expect.any(File),
      expect.objectContaining({ keyColumn: 0, courseColumn: 1, teacherColumn: 2 }),
      expect.objectContaining({ semesterId: 7, applySuggestedRule: true }),
    )
    expect(wrapper.get('[data-testid="manual-commit-result"]').text()).toContain('写入 546 节')

    await wrapper.get('[data-testid="open-save-recipe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="recipe-name"]').setValue('八年级长表')
    await wrapper.get('[data-testid="save-recipe-button"]').trigger('click')
    await flushPromises()

    expect(importsMock.saveImportRecipe).toHaveBeenCalledTimes(1)
    const payload = importsMock.saveImportRecipe.mock.calls[0][0]
    expect(payload.name).toBe('八年级长表')
    expect(payload.layout).toBe('LONG')
    expect(payload.fingerprint.sheetName).toBe('CSV')
    expect(payload.expected).toEqual({
      slotCount: 560,
      lessonCount: 546,
      emptySlotCount: 14,
      teacherCount: 48,
      courseCount: 16,
    })
    // 样例文件随保存一起上传，服务端会用它重跑。
    expect(importsMock.saveImportRecipe.mock.calls[0][1]).toBeInstanceOf(File)
    expect(wrapper.text()).toContain('已保存配方')
  })

  it('保存配方样例测试失败时展示逐项差异', async () => {
    importsMock.saveImportRecipe.mockRejectedValue({
      response: {
        data: {
          message: '样例测试未通过，数字对不上：空槽数预期 14、实际 80',
          differences: [{ field: 'emptySlotCount', label: '空槽数', expected: 14, actual: 80 }],
        },
      },
    })
    const { wrapper } = await mountView()
    await chooseFile(wrapper)
    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="manual-commit"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="open-save-recipe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="recipe-name"]').setValue('会失败的配方')
    await wrapper.get('[data-testid="save-recipe-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="recipe-save-differences"]').text()).toContain('空槽数')
    expect(wrapper.get('[data-testid="recipe-save-differences"]').text()).toContain('80')
  })

  it('预览结果与配方预期差异过大时给出醒目提醒', async () => {
    const withExpected = {
      ...exactMatch(),
      expected: { slotCount: 560, lessonCount: 546, emptySlotCount: 2, teacherCount: 48, courseCount: 16 },
    }
    importsMock.matchImportRecipe.mockResolvedValue(withExpected)
    importsMock.previewManualImport.mockResolvedValue({ ...PREVIEW, recipeMatch: withExpected })
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    expect(wrapper.get('[data-testid="recipe-count-warning"]').text()).toContain('空槽数与配方预期差异过大')
  })

  it('入库报错时展示行级原因且不显示保存配方入口', async () => {
    importsMock.commitManualImport.mockResolvedValue({
      draftId: null,
      imported: 0,
      skippedEmpty: 0,
      errors: [{ row: 12, field: 'className', message: '系统中找不到班级：八（99）', candidates: [] }],
      warnings: [],
      appliedRecipeId: null,
      appliedRule: null,
    })
    const { wrapper } = await mountView()
    await chooseFile(wrapper)
    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="manual-commit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="manual-commit-errors"]').text()).toContain('八（99）')
    expect(wrapper.find('[data-testid="manual-save-recipe"]').exists()).toBe(false)
  })

  it('AI 可用且没有配方时给出建议，采用后填入现有列映射', async () => {
    httpGet(true)
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    expect(wrapper.find('[data-testid="ai-suggest-button"]').exists()).toBe(true)
    await wrapper.get('[data-testid="ai-suggest-button"]').trigger('click')
    await flushPromises()

    expect(importsMock.suggestImportColumns).toHaveBeenCalledWith(expect.any(File), 'CSV', 0)
    expect(wrapper.get('[data-testid="ai-mapping-summary"]').text()).toContain('复合键')
    expect(wrapper.get('[data-testid="ai-reasoning"]').text()).toContain('复合键映射')
    expect(wrapper.get('[data-testid="ai-preview-summary"]').text()).toContain('560')

    await wrapper.get('[data-testid="adopt-ai-suggestion"]').trigger('click')
    await flushPromises()

    // 采用后只是填表；用户仍要自己点“预览”，走的还是 05 的流程。
    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()
    expect(importsMock.previewManualImport).toHaveBeenCalledWith(
      expect.any(File),
      expect.objectContaining({ keyColumn: 0, courseColumn: 1, teacherColumn: 2 }),
      7,
    )
  })

  it('AI 建议失败时只提示一句，手动表单保持不变', async () => {
    httpGet(true)
    importsMock.suggestImportColumns.mockResolvedValue({
      available: true,
      success: false,
      error: 'AI 建议的列映射没有解析出任何数据行',
      raw: 'not-json',
    })
    const warning = vi.spyOn(ElMessage, 'warning')
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    await wrapper.get('[data-testid="ai-suggest-button"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="ai-suggestion"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="manual-mapper"]').exists()).toBe(true)
    expect(importsMock.previewManualImport).not.toHaveBeenCalled()
  })

  it('AI 未配置时隐藏入口并提示可手动选列', async () => {
    const { wrapper } = await mountView()
    await chooseFile(wrapper)

    expect(wrapper.find('[data-testid="ai-suggest-button"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="ai-unavailable-hint"]').text()).toContain('AI 未配置')
  })

  it('采用 AI 建议后保存配方，快照带上原始输出与建议映射', async () => {
    httpGet(true)
    const { wrapper } = await mountView()
    await chooseFile(wrapper)
    await wrapper.get('[data-testid="ai-suggest-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="adopt-ai-suggestion"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="manual-preview-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="manual-commit"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="open-save-recipe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="recipe-name"]').setValue('AI 配方')
    await wrapper.get('[data-testid="save-recipe-button"]').trigger('click')
    await flushPromises()

    const payload = importsMock.saveImportRecipe.mock.calls[0][0]
    expect(payload.aiSuggested).toBe(true)
    expect(payload.aiRawResponse).toContain('compositeColumn')
    expect(payload.aiSuggestedMapping).toEqual(
      expect.objectContaining({ keyColumn: 0, courseColumn: 1, teacherColumn: 2 }),
    )
    // 用户最终确认的 mapping 仍是单独的一份。
    expect(payload.columns).toEqual(expect.objectContaining({ keyColumn: 0, courseColumn: 1 }))
  })
})
