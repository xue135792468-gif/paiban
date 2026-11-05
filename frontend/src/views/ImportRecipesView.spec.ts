import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ImportRecipesView from './ImportRecipesView.vue'
import type { ImportRecipe, RecipeVersion } from '@/api/imports'

const apiMock = vi.hoisted(() => ({
  listImportRecipes: vi.fn(),
  getImportRecipe: vi.fn(),
  testImportRecipe: vi.fn(),
  updateImportRecipe: vi.fn(),
  listImportRecipeVersions: vi.fn(),
  rollbackImportRecipe: vi.fn(),
  deleteImportRecipe: vi.fn(),
}))

vi.mock('@/api/imports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/imports')>()
  return { ...actual, ...apiMock }
})

function recipe(overrides: Partial<ImportRecipe> = {}): ImportRecipe {
  return {
    id: 3,
    name: '八年级配方',
    layout: 'LONG',
    fingerprint: {
      sheetName: 'CSV',
      headerRow: 0,
      columnCount: 3,
      headers: ['教师', '班级|星期|节次', '课程'],
      delimiter: '|',
      layout: 'LONG',
    },
    columns: {
      sheetName: 'CSV',
      headerRow: 0,
      delimiter: '|',
      keyColumn: 0,
      keyParts: ['className', 'dayOfWeek', 'periodNo'],
      courseColumn: 1,
      teacherColumn: 2,
      ignoreBlankRows: true,
    },
    options: {},
    expected: {
      slotCount: 4,
      lessonCount: 3,
      emptySlotCount: 1,
      teacherCount: 2,
      courseCount: 2,
    },
    sample: { fileName: 'sample.csv', size: 120, sha256: 'abc' },
    version: 2,
    enabled: true,
    createdBy: 'admin',
    createdAt: '2026-11-01T02:00:00Z',
    lastUsedAt: '2026-11-02T02:00:00Z',
    ...overrides,
  }
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/data/recipes', component: { template: '<div />' } },
      { path: '/data/import/manual', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/data/recipes')
  await router.isReady()
  const wrapper = mount(ImportRecipesView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('导入配方管理页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.listImportRecipes.mockResolvedValue([recipe()])
    apiMock.testImportRecipe.mockResolvedValue({
      pass: true,
      actual: { slotCount: 4, lessonCount: 3, emptySlotCount: 1, teacherCount: 2, courseCount: 2 },
      expected: { slotCount: 4, lessonCount: 3, emptySlotCount: 1, teacherCount: 2, courseCount: 2 },
      differences: [],
    })
  })

  it('列表显示版本、状态与最后使用，查看时自动跑样例测试', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.text()).toContain('八年级配方')
    expect(wrapper.text()).toContain('v2')
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).toContain('长表')

    await wrapper.get('[data-testid="recipe-detail"]').trigger('click')
    await flushPromises()

    expect(apiMock.testImportRecipe).toHaveBeenCalledWith(3)
    expect(document.body.textContent).toContain('sample.csv')
    expect(document.body.textContent).toContain('样例测试通过')
    expect(document.body.textContent).toContain('预期结果')
  })

  it('保存修改时样例测试失败，展示逐项差异且不刷新列表', async () => {
    apiMock.updateImportRecipe.mockRejectedValue({
      response: {
        data: {
          message: '样例测试未通过，数字对不上：有课数预期 3、实际 0',
          differences: [{ field: 'lessonCount', label: '有课数', expected: 3, actual: 0 }],
        },
      },
    })
    const { wrapper } = await mountView()

    await wrapper.get('[data-testid="recipe-edit"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="recipe-edit-save"]').trigger('click')
    await flushPromises()

    expect(apiMock.updateImportRecipe).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="recipe-edit-differences"]').text()).toContain('有课数')
    expect(apiMock.listImportRecipes).toHaveBeenCalledTimes(1)
  })

  it('版本弹窗列出历史并回退到指定版本', async () => {
    const versions: RecipeVersion[] = [
      { version: 2, createdAt: '2026-11-02T02:00:00Z', createdBy: 'admin', summary: 'LONG · 预期 4 槽' },
      { version: 1, createdAt: '2026-11-01T02:00:00Z', createdBy: 'admin', summary: 'LONG · 预期 4 槽' },
    ]
    apiMock.listImportRecipeVersions.mockResolvedValue(versions)
    apiMock.rollbackImportRecipe.mockResolvedValue(recipe({ version: 3 }))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const { wrapper } = await mountView()
    await wrapper.get('[data-testid="recipe-versions"]').trigger('click')
    await flushPromises()

    const buttons = wrapper.findAll('[data-testid="recipe-rollback"]')
    expect(buttons).toHaveLength(2)
    await buttons[1].trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(apiMock.rollbackImportRecipe).toHaveBeenCalledWith(3, 1)
    expect(apiMock.listImportRecipes).toHaveBeenCalledTimes(2)
  })

  it('删除前提示最近删除，确认后删除并刷新', async () => {
    apiMock.deleteImportRecipe.mockResolvedValue({ batchId: 'batch-1', deletedCount: 1 })
    apiMock.listImportRecipes
      .mockResolvedValueOnce([recipe()])
      .mockResolvedValueOnce([])
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const { wrapper } = await mountView()
    await wrapper.get('[data-testid="recipe-delete"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('7 天内可恢复'),
      '删除配方',
      expect.anything(),
    )
    expect(apiMock.deleteImportRecipe).toHaveBeenCalledWith(3)
    expect(apiMock.listImportRecipes).toHaveBeenCalledTimes(2)
  })
})
