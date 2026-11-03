import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ManualImportView from './ManualImportView.vue'
import { useAuthStore } from '@/stores/auth'
import type { ManualInspect, ManualPreview, RecipeMatch } from '@/api/imports'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const importsMock = vi.hoisted(() => ({
  inspectManualImport: vi.fn(),
  previewManualImport: vi.fn(),
  commitManualImport: vi.fn(),
  matchImportRecipe: vi.fn(),
  listImportRecipes: vi.fn(),
  saveImportRecipe: vi.fn(),
}))
vi.mock('@/api/imports', () => importsMock)

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
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') {
        return Promise.resolve({ data: [{ id: 7, name: '2025 秋季学期', current: true }] })
      }
      if (url === '/teachers') {
        return Promise.resolve({ data: [{ id: 3, name: '张三' }, { id: 4, name: '李四' }] })
      }
      return Promise.resolve({ data: [] })
    })
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
    expect(wrapper.text()).toContain('已保存配方')
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
})
