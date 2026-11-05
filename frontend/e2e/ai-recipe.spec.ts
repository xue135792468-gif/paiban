import { expect, request, test, type APIRequestContext } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { apiCall, apiLogin, clearImportRecipes, uiLogin } from './helpers'

/**
 * 票 07：没有匹配配方时，让 AI 分析表格结构并给出列映射建议。
 *
 * 模型用本地 HTTP stub 代替，永远不访问外网：stub 的回答必须被服务端重新预览验证，
 * 采用后走的仍是 05 的手动预览/入库/保存配方。第二个用例把模型关掉，证明页面只是
 * 回到手动选列，不报错。
 */

interface AiRecipeSeed {
  suffix: string
  semesterId: number
  compositeCsv: string
  separateCsv: string
}

const CSV = (name: string, content: string) => ({
  name,
  mimeType: 'text/csv',
  buffer: Buffer.from(content, 'utf-8'),
})

async function seedAiRecipeScenario(api: APIRequestContext): Promise<AiRecipeSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 7)
  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `AI 配方学期-${suffix}`,
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
  for (const name of [classA, classB]) {
    await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
      gradeId: grade.id,
      name,
      studentCount: 45,
    })
  }
  const teacherA = `张老师${suffix}`
  const teacherB = `李老师${suffix}`
  await apiCall(api, token, 'post', '/teachers', { name: teacherA, employeeNo: `AR-${suffix}` })
  await apiCall(api, token, 'post', '/teachers', { name: teacherB, employeeNo: `BR-${suffix}` })
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
  const composite = [`班级|星期|节次,课程,教师,备注-${suffix}`]
  const separate = [`班级,星期,节次,课程,教师,备注-${suffix}`]
  for (const className of [classA, classB]) {
    for (const [index, day] of days.entries()) {
      const course = index % 2 ? courseB : courseA
      const teacher = index % 2 ? teacherB : teacherA
      composite.push(`${className}|${day}|${index + 1},${course},${teacher},`)
      separate.push(`${className},${day},${index + 1},${course},${teacher},`)
    }
  }
  return {
    suffix,
    semesterId: semester.id,
    compositeCsv: composite.join('\n'),
    separateCsv: separate.join('\n'),
  }
}

test.describe.serial('AI 生成导入配方', () => {
  let server: Server
  let api: APIRequestContext
  let token: string
  let seed: AiRecipeSeed
  let providerId: number
  let reply = ''

  test.beforeAll(async () => {
    server = createServer((incoming, response) => {
      if (incoming.method !== 'POST' || !incoming.url?.includes('/chat/completions')) {
        response.writeHead(404)
        response.end()
        return
      }
      incoming.resume()
      incoming.on('end', () => {
        response.writeHead(200, { 'Content-Type': 'application/json' })
        response.end(
          JSON.stringify({
            choices: [
              { message: { role: 'assistant', content: reply }, finish_reason: 'stop' },
            ],
            usage: { prompt_tokens: 12, completion_tokens: 34 },
          }),
        )
      })
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const port = (server.address() as AddressInfo).port

    api = await request.newContext()
    await clearImportRecipes(api)
    token = await apiLogin(api)
    seed = await seedAiRecipeScenario(api)
    reply = JSON.stringify({
      layout: 'LONG',
      sheetName: 'CSV',
      headerRow: 0,
      delimiter: '|',
      compositeColumn: 0,
      classColumn: null,
      dayColumn: null,
      periodColumn: null,
      courseColumn: 1,
      teacherColumn: 2,
      ignoreColumns: [],
      reasoning: '班级、星期、节次在复合列里，课程和教师各占一列。',
    })
    const provider = await apiCall(api, token, 'post', '/ai-providers', {
      name: `本机替身-${seed.suffix}`,
      type: 'custom',
      protocol: 'openai',
      baseUrl: `http://127.0.0.1:${port}/v1`,
      apiKey: 'stub-key',
      enabled: true,
      models: [{ modelName: 'stub-model', displayName: 'Stub', enabled: true }],
    })
    providerId = provider.id
    await apiCall(api, token, 'put', '/ai-task-config', {
      mode: 'unified',
      providerId: provider.id,
      modelId: provider.models[0].id,
    })
  })

  test.afterAll(async () => {
    if (providerId) {
      await apiCall(api, token, 'delete', `/ai-providers/${providerId}`).catch(() => undefined)
    }
    await new Promise<void>((resolve) => server.close(() => resolve()))
    await api.dispose()
  })

  test('AI 建议 → 采用 → 预览 → 入库 → 保存配方', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/import/manual')

    await page.getByTestId('manual-file').setInputFiles(
      CSV('AI 配方.csv', seed.compositeCsv),
    )
    await expect(page.getByTestId('manual-mapper')).toBeVisible()
    await expect(page.getByTestId('ai-suggest-button')).toBeVisible()

    await page.getByTestId('ai-suggest-button').click()
    await expect(page.getByTestId('ai-suggestion')).toBeVisible()
    await expect(page.getByTestId('ai-mapping-summary')).toContainText('复合键')
    await expect(page.getByTestId('ai-reasoning')).toContainText('复合列')
    await expect(page.getByTestId('ai-preview-summary')).toContainText('10 槽')

    await page.getByTestId('adopt-ai-suggestion').click()
    await page.getByTestId('manual-preview-button').click()
    await expect(page.getByTestId('stat-slots')).toContainText('10')
    await expect(page.getByTestId('stat-lessons')).toContainText('10')
    await expect(page.getByTestId('stat-empty')).toContainText('0')

    await page.getByTestId('manual-draft-name').fill(`AI 导入草稿-${seed.suffix}`)
    await page.getByTestId('manual-commit').click()
    await expect(page.getByTestId('manual-commit-result')).toContainText('写入 10 节')

    await page.getByTestId('open-save-recipe').click()
    await page.getByTestId('recipe-name').fill(`AI 配方-${seed.suffix}`)
    await page.getByTestId('save-recipe-button').click()
    await expect(page.getByTestId('recipe-saved')).toContainText(`AI 配方-${seed.suffix}`)
  })

  test('AI 关掉后不显示入口，手动选列仍可用', async ({ page }) => {
    // 显式清空任务绑定：不依赖前面用例或别的 spec 留下的配置。
    await apiCall(api, token, 'put', '/ai-task-config', { mode: 'custom', tasks: [] })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/import/manual')
    await page.getByTestId('manual-file').setInputFiles(
      CSV('分列课表.csv', seed.separateCsv),
    )
    await expect(page.getByTestId('manual-mapper')).toBeVisible()

    await expect(page.getByTestId('ai-unavailable-hint')).toContainText('AI 未配置，可手动选列')
    await expect(page.getByTestId('ai-suggest-button')).toHaveCount(0)

    await page.getByTestId('manual-preview-button').click()
    await expect(page.getByTestId('stat-slots')).toContainText('10')
    await expect(page.getByTestId('stat-lessons')).toContainText('10')
  })
})
