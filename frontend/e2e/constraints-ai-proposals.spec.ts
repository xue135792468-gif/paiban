import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
  pickOption,
} from './helpers'
import { capture } from './screenshots'

/**
 * Ticket 14/08: read-only natural-language proposals against a local HTTP stub
 * provider. The real model is never called; the stub answers exactly like an
 * OpenAI-compatible provider so the run, normalization, cancel and record paths are
 * exercised end to end.
 */
test.describe.serial('约束配置：自然语言只读建议', () => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const teacherName = `王明${suffix}`

  let server: Server
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed
  let reply = ''
  let delayMs = 0

  test.beforeAll(async () => {
    server = createServer((request, response) => {
      if (request.method !== 'POST' || !request.url?.includes('/chat/completions')) {
        response.writeHead(404)
        response.end()
        return
      }
      request.resume()
      request.on('end', () => {
        setTimeout(() => {
          response.writeHead(200, { 'Content-Type': 'application/json' })
          response.end(
            JSON.stringify({
              choices: [
                { message: { role: 'assistant', content: reply }, finish_reason: 'stop' },
              ],
              usage: { prompt_tokens: 12, completion_tokens: 34 },
            }),
          )
        }, delayMs)
      })
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const port = (server.address() as AddressInfo).port

    api = await newContext()
    token = await apiLogin(api)
    seed = await seedConstraintScenario(api)
    await apiCall(api, token, 'post', '/teachers', {
      name: teacherName,
      employeeNo: `AI${suffix}`,
      subject: '语文',
    })
    const provider = await apiCall(api, token, 'post', '/ai-providers', {
      name: `本机替身-${suffix}`,
      type: 'custom',
      protocol: 'openai',
      baseUrl: `http://127.0.0.1:${port}/v1`,
      apiKey: 'stub-key',
      enabled: true,
      models: [{ modelName: 'stub-model', displayName: 'Stub', enabled: true }],
    })
    await apiCall(api, token, 'put', '/ai-task-config', {
      mode: 'unified',
      providerId: provider.id,
      modelId: provider.models[0].id,
    })
    await apiCall(api, token, 'put', '/settings', {
      'ai.features.natural-language-constraints': 'true',
    })
  })

  test.afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()))
    await api.dispose()
  })

  test.beforeEach(() => {
    delayMs = 0
    reply = JSON.stringify({
      proposals: [
        {
          sourceText: '王老师周三下午不排课',
          type: 'TEACHER_UNAVAILABLE',
          strength: 'HARD',
          status: 'READY',
          explanation: '周三下午全部禁排',
          teacherName: '王老师',
          days: [3],
          section: 'AFTERNOON',
        },
      ],
    })
  })

  async function openPanel(page: Page) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-ai-panel')).toBeVisible()
  }


  test('生成建议：按条展示，不写入生效配置', async ({ page }) => {
    const before = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })

    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.getByTestId('constraint-ai-input').fill('王老师周三下午不排课')
    await page.getByTestId('constraint-ai-generate').click()

    await expect(page.getByTestId('constraint-ai-run')).toBeVisible()
    await expect(page.getByTestId('constraint-ai-counts')).toContainText('1 条独立建议')
    const cards = page.getByTestId('constraint-proposal')
    await expect(cards).toHaveCount(1)
    await expect(cards.first().getByTestId('constraint-proposal-status')).toContainText('可加入')
    await expect(cards.first().getByTestId('constraint-proposal-strength')).toContainText('必须')
    await expect(cards.first().getByTestId('constraint-proposal-entries').locator('li')).toHaveCount(4)
    await expect(page.getByTestId('constraint-ai-note')).toContainText('需要你校对')
    await capture(page, 'constraint-ai-proposal')

    // Generating changes nothing that is in force.
    const after = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    expect(JSON.stringify(after)).toBe(JSON.stringify(before))
  })

  test('生成记录可重新打开并查看原始输出', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.getByTestId('constraint-ai-history').locator('.el-collapse-item__header').click()
    await expect(page.getByTestId('constraint-ai-history-item').first()).toBeVisible()
    await page.getByTestId('constraint-ai-history-item').first().click()
    await expect(page.getByTestId('constraint-ai-history-detail')).toContainText(
      '王老师周三下午不排课',
    )
    await page.getByTestId('constraint-ai-history-detail').locator('summary').click()
    await expect(page.getByTestId('constraint-ai-raw')).toContainText('TEACHER_UNAVAILABLE')
  })

  test('取消等待后页面不填回晚到的结果', async ({ page }) => {
    delayMs = 4000
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.getByTestId('constraint-ai-input').fill('王老师周三下午不排课')
    await page.getByTestId('constraint-ai-generate').click()
    await expect(page.getByTestId('constraint-ai-wait')).toBeVisible()
    await page.getByTestId('constraint-ai-cancel').click()

    await expect(page.getByTestId('constraint-ai-run-cancelled')).toBeVisible()
    await expect(page.getByTestId('constraint-proposal')).toHaveCount(0)
    // The late provider answer is recorded, not rendered.
    await expect(page.getByTestId('constraint-ai-run')).toHaveCount(0)
    await expect(page.getByTestId('constraint-ai-input')).toHaveValue('王老师周三下午不排课')
  })

  test('勾选加入后仍是未保存，统一保存并留下确认记录', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.getByTestId('constraint-ai-input').fill('王老师周三下午不排课')
    await page.getByTestId('constraint-ai-generate').click()
    await expect(page.getByTestId('constraint-ai-run')).toBeVisible()

    await page.getByTestId('constraint-proposal-select').first().click()
    await page.getByTestId('constraint-ai-add').click()
    await expect(page.getByTestId('constraint-ai-outcomes')).toContainText('已加入')
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()

    await page.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    const cards = page.getByTestId('constraint-entry')
    await expect(cards).toHaveCount(1)
    await expect(cards.first().getByTestId('constraint-entry-summary')).toContainText('周三第5节')
    await expect(cards.first().getByTestId('constraint-entry-summary')).toContainText('周三第8节')

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const unavailable = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    expect(unavailable.entries).toHaveLength(4)
    expect(new Set(unavailable.entries.map((entry: any) => entry.groupKey)).size).toBe(1)

    const audits = await apiCall(api, token, 'get', '/constraint-audits', undefined, {
      semesterId: seed.semesterId,
    })
    expect(audits.length).toBeGreaterThan(0)
    expect(audits[0].runId).toBeTruthy()
    expect(audits[0].decisions[0].action).toBe('ADDED')
    expect(audits[0].finalConfigs).toBeTruthy()
  })

  test('手工修改在加入 AI 建议后仍然保留', async ({ page }) => {
    const manualTeacher = seed.teachers[1]
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await page.getByTestId('constraint-entry-add').click()
    const manual = page.getByTestId('constraint-entry').last()
    await pickOption(manual, 'constraint-entry-teacher', manualTeacher.name)
    const slot = manual.getByTestId('constraint-entry-slot').first()
    await pickOption(slot, 'constraint-entry-slot-day', '周一')
    await pickOption(slot, 'constraint-entry-slot-period', '第1节')

    reply = JSON.stringify({
      proposals: [
        {
          sourceText: '李老师周四下午不排课',
          type: 'TEACHER_UNAVAILABLE',
          strength: 'HARD',
          status: 'READY',
          teacherName: manualTeacher.name,
          days: [4],
          section: 'AFTERNOON',
        },
      ],
    })
    await page.getByTestId('constraint-ai-input').fill('李老师周四下午不排课')
    await page.getByTestId('constraint-ai-generate').click()
    await expect(page.getByTestId('constraint-ai-run')).toBeVisible()
    await page.getByTestId('constraint-proposal-select').first().click()
    await page.getByTestId('constraint-ai-add').click()
    await expect(page.getByTestId('constraint-ai-outcomes')).toContainText('已加入')

    // The manual card is still there next to the earlier AI card and the new one.
    await expect(page.getByTestId('constraint-entry')).toHaveCount(3)
    const manualCard = page
      .getByTestId('constraint-entry')
      .filter({ hasText: manualTeacher.name })
      .filter({ hasText: '周一第1节' })
    await expect(manualCard).toHaveCount(1)
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()
  })

  test('同名教师由用户选择后才加入', async ({ page }) => {
    const first = `张伟甲${suffix}`
    const second = `张伟乙${suffix}`
    const firstCreated = await apiCall(api, token, 'post', '/teachers', {
      name: first,
      employeeNo: `Z1${suffix}`,
      subject: '数学',
    })
    await apiCall(api, token, 'post', '/teachers', {
      name: second,
      employeeNo: `Z2${suffix}`,
      subject: '数学',
    })
    await apiCall(api, token, 'post', '/courses', {
      name: `体育${suffix}`,
      subject: '体育',
      allowConsecutive: false,
    })
    reply = JSON.stringify({
      proposals: [
        {
          sourceText: '张老师周三下午不排',
          type: 'TEACHER_UNAVAILABLE',
          strength: 'HARD',
          status: 'NEEDS_INPUT',
          teacherName: '张老师',
          days: [3],
          section: 'AFTERNOON',
        },
        {
          sourceText: '体育不排晚自习',
          type: 'COURSE_NOT_IN_EVENING',
          strength: 'HARD',
          status: 'READY',
          courseName: `体育${suffix}`,
        },
      ],
    })

    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await page.getByTestId('constraint-ai-input').fill('张老师周三下午不排，体育不排晚自习。')
    await page.getByTestId('constraint-ai-generate').click()
    await expect(page.getByTestId('constraint-ai-run')).toBeVisible()

    const teacherCard = page.getByTestId('constraint-proposal').first()
    await expect(teacherCard.getByTestId('constraint-proposal-status')).toContainText('待补齐')
    await pickOption(teacherCard, 'constraint-proposal-candidate', first)
    await expect(teacherCard.getByTestId('constraint-proposal-status')).toContainText('可加入')

    await teacherCard.getByTestId('constraint-proposal-select').click()
    await page.getByTestId('constraint-ai-add').click()
    await expect(page.getByTestId('constraint-ai-outcomes')).toContainText('已加入')

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const unavailable = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    const newIds = [firstCreated.id]
    const chosen = unavailable.entries.find(
      (entry: any) => newIds.includes(entry.params.teacherId) && entry.params.dayOfWeek === 3,
    )
    expect(chosen).toBeTruthy()
    expect(chosen.params.teacherId).toBe(firstCreated.id)
  })

  test('AI 关闭时保留入口并说明原因', async ({ page }) => {
    await apiCall(api, token, 'put', '/settings', {
      'ai.features.natural-language-constraints': 'false',
    })
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await expect(page.getByTestId('constraint-ai-unavailable')).toContainText('未开启')
    await expect(page.getByTestId('constraint-ai-unavailable')).toContainText('手动配置始终可用')
    await expect(page.getByTestId('constraint-ai-generate')).toHaveCount(0)
    await capture(page, 'constraint-ai-unavailable')

    await apiCall(api, token, 'put', '/settings', {
      'ai.features.natural-language-constraints': 'true',
    })
  })
})
