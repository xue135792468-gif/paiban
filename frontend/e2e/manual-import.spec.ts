import { expect, request, test, type APIRequestContext } from '@playwright/test'
import { apiCall, apiLogin, clearImportRecipes, uiLogin } from './helpers'

/**
 * 票 05：没有 AI 的手动选列导入。
 *
 * 整个流程只依赖运行期生成的 CSV：上传 → 选表/选列 → 预览统计 → 入库生成草稿 →
 * 保存配方 → 再次上传同格式文件自动套用。没有 stub 模型，因此这条用例同时证明
 * 这条路径不调用 AI。
 */

interface ManualImportSeed {
  suffix: string
  semesterId: number
  csv: string
}

const CSV = (seed: ManualImportSeed) => ({
  name: '手动导入课表.csv',
  mimeType: 'text/csv',
  buffer: Buffer.from(seed.csv, 'utf-8'),
})

async function seedManualImport(api: APIRequestContext): Promise<ManualImportSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 7)
  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `手动导入学期-${suffix}`,
    startDate: '2026-09-01',
    endDate: '2027-01-20',
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 8,
    afternoonCount: 0,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })
  const grade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `八年级${suffix}`,
    sortOrder: 1,
  })
  const classA = `八（1）${suffix}`
  const classB = `八（2）${suffix}`
  await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: classA,
    studentCount: 45,
  })
  await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: classB,
    studentCount: 45,
  })
  const teacherA = `张老师${suffix}`
  const teacherB = `李老师${suffix}`
  await apiCall(api, token, 'post', '/teachers', { name: teacherA, employeeNo: `MA-${suffix}` })
  await apiCall(api, token, 'post', '/teachers', { name: teacherB, employeeNo: `MB-${suffix}` })
  const courseA = `语文${suffix}`
  const courseB = `数学${suffix}`
  await apiCall(api, token, 'post', '/courses', {
    name: courseA,
    subject: courseA,
    allowConsecutive: false,
  })
  await apiCall(api, token, 'post', '/courses', {
    name: courseB,
    subject: courseB,
    allowConsecutive: false,
  })

  const days = ['星期一', '星期二', '星期三', '星期四', '星期五']
  // A per-run extra column keeps the fingerprint distinct from the other import
  // specs in the same Playwright database; the mapper ignores unknown columns.
  const lines = [`班级|星期|节次,课程,教师,备注-${suffix}`]
  for (const className of [classA, classB]) {
    for (const [index, day] of days.entries()) {
      lines.push(`${className}|${day}|${index + 1},${index % 2 ? courseB : courseA},${index % 2 ? teacherB : teacherA},`)
    }
  }
  return { suffix, semesterId: semester.id, csv: lines.join('\n') }
}

test.describe('手动选列导入', () => {
  let api: APIRequestContext
  let seed: ManualImportSeed

  test.beforeAll(async () => {
    api = await request.newContext()
    await clearImportRecipes(api)
    seed = await seedManualImport(api)
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  test('选列预览入库后保存配方，第二次上传自动套用', async ({ page }) => {
    // 这条路径必须完全没有 AI 分析：只记录真正会调用模型/建议的端点；
    // /api/ai/status 之类的可用性探测不算 AI 调用。
    const aiRequests: string[] = []
    page.on('request', (request) => {
      const url = request.url()
      if (url.includes('/ai-suggest') || url.includes('/chat/completions')) {
        aiRequests.push(url)
      }
    })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/import/manual')

    await page.getByTestId('manual-file').setInputFiles(CSV(seed))
    await expect(page.getByTestId('manual-mapper')).toBeVisible()

    await page.getByTestId('manual-preview-button').click()
    await expect(page.getByTestId('stat-slots')).toContainText('10')
    await expect(page.getByTestId('stat-lessons')).toContainText('10')
    await expect(page.getByTestId('stat-empty')).toContainText('0')
    await expect(page.getByTestId('recipe-match')).toContainText('未找到配方')
    // 种子里的教师都已存在，占位提醒不应拦住入库。
    await expect(page.getByTestId('placeholder-pending')).toHaveCount(0)

    await page.getByTestId('manual-draft-name').fill(`手动导入草稿-${seed.suffix}`)
    await page.getByTestId('manual-commit').click()
    await expect(page.getByTestId('manual-commit-result')).toContainText('写入 10 节')

    await page.getByTestId('open-save-recipe').click()
    await page.getByTestId('recipe-name').fill(`手动导入配方-${seed.suffix}`)
    await page.getByTestId('save-recipe-button').click()
    await expect(page.getByTestId('recipe-saved')).toContainText(`手动导入配方-${seed.suffix}`)

    // 入库后的草稿能直接打开：点链接进课表工作台，而不是只弹一句成功。
    await page.getByRole('link', { name: '打开草稿' }).click()
    await expect(page).toHaveURL(/\/workbench\/drafts\/\d+/)
    await expect(page.locator('.draft-page')).toBeVisible()

    // 第二次上传同格式文件：识别后自动匹配配方并直接出预览，不用再点“预览”。
    await page.goto('/data/import/manual')
    await page.getByTestId('manual-file').setInputFiles(CSV(seed))
    await expect(page.getByTestId('recipe-match')).toContainText('已套用配方')
    await expect(page.getByTestId('recipe-match')).toContainText(`手动导入配方-${seed.suffix}`)
    await expect(page.getByTestId('stat-lessons')).toContainText('10')

    expect(aiRequests).toEqual([])
  })
})
