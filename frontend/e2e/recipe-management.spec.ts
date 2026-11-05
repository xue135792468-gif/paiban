import { expect, request, test, type APIRequestContext } from '@playwright/test'
import { apiCall, apiLogin, clearImportRecipes, uiLogin } from './helpers'

/**
 * 票 06：配方管理。
 *
 * 手动导入保存配方（保存即样例测试）→ 配方页查看并重跑样例 → 删除进最近删除 →
 * 从回收站恢复。只依赖运行期生成的 CSV，不调用 AI。
 */

interface RecipeSeed {
  suffix: string
  semesterId: number
  csv: string
}

const recipeCsv = (seed: RecipeSeed) => ({
  name: `配方样例-${seed.suffix}.csv`,
  mimeType: 'text/csv',
  buffer: Buffer.from(seed.csv, 'utf-8'),
})

async function seedRecipeScenario(api: APIRequestContext): Promise<RecipeSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 7)
  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `配方学期-${suffix}`,
    startDate: '2026-09-01',
    endDate: '2027-01-20',
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    periodCount: 8,
    days: [1, 2, 3, 4, 5],
  })
  const grade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `八年级${suffix}`,
    sortOrder: 1,
  })
  const classA = `八（1）${suffix}`
  const classB = `八（2）${suffix}`
  for (const name of [classA, classB]) {
    await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
      gradeId: grade.id,
      name,
      studentCount: 45,
    })
  }
  const teacherA = `张老师${suffix}`
  const teacherB = `李老师${suffix}`
  await apiCall(api, token, 'post', '/teachers', { name: teacherA, employeeNo: `RA-${suffix}` })
  await apiCall(api, token, 'post', '/teachers', { name: teacherB, employeeNo: `RB-${suffix}` })
  const courseA = `语文${suffix}`
  const courseB = `数学${suffix}`
  for (const name of [courseA, courseB]) {
    await apiCall(api, token, 'post', '/courses', {
      name,
      subject: name,
      allowConsecutive: false,
    })
  }

  const days = ['星期一', '星期二', '星期三', '星期四', '星期五']
  // A per-run extra column keeps the fingerprint distinct from the other import
  // specs in the same Playwright database; the mapper ignores unknown columns.
  const lines = [`班级|星期|节次,课程,教师,备注-${suffix}`]
  for (const className of [classA, classB]) {
    for (const [index, day] of days.entries()) {
      lines.push(
        `${className}|${day}|${index + 1},${index % 2 ? courseB : courseA},${index % 2 ? teacherB : teacherA},`,
      )
    }
  }
  return { suffix, semesterId: semester.id, csv: lines.join('\n') }
}

test.describe('导入配方管理', () => {
  let api: APIRequestContext
  let seed: RecipeSeed
  const recipeName = () => `配方-${seed.suffix}`

  test.beforeAll(async () => {
    api = await request.newContext()
    await clearImportRecipes(api)
    seed = await seedRecipeScenario(api)
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  test('保存配方后查看测试、删除并恢复', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/import/manual')

    await page.getByTestId('manual-file').setInputFiles(recipeCsv(seed))
    await expect(page.getByTestId('manual-mapper')).toBeVisible()
    await page.getByTestId('manual-preview-button').click()
    await expect(page.getByTestId('stat-slots')).toContainText('10')

    await page.getByTestId('manual-draft-name').fill(`配方草稿-${seed.suffix}`)
    await page.getByTestId('manual-commit').click()
    await expect(page.getByTestId('manual-commit-result')).toContainText('写入 10 节')

    await page.getByTestId('open-save-recipe').click()
    await page.getByTestId('recipe-name').fill(recipeName())
    await page.getByTestId('save-recipe-button').click()
    await expect(page.getByTestId('recipe-saved')).toContainText(recipeName())

    // 配方页：查看抽屉自动跑样例测试，结果稳定可见。
    await page.goto('/data/recipes')
    const row = page.getByRole('row', { name: new RegExp(recipeName()) })
    await expect(row).toBeVisible()
    await expect(row).toContainText('v1')
    await row.getByTestId('recipe-detail').click()
    await expect(page.getByTestId('recipe-detail-test')).toContainText('样例测试通过')
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('recipe-detail-drawer')).toBeHidden()

    // 删除 → 最近删除 → 恢复。
    await row.getByTestId('recipe-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(row).toBeHidden()

    await page.goto('/settings/recycle-bin')
    const deletedRow = page.getByRole('row', { name: new RegExp(recipeName()) })
    await expect(deletedRow).toContainText('导入配方')
    await deletedRow.getByTestId('restore-batch').click()

    await page.goto('/data/recipes')
    await expect(page.getByRole('row', { name: new RegExp(recipeName()) })).toBeVisible()
  })
})
