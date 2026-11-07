import { expect as baseExpect, test, type APIRequestContext, type Page } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'

// 全量单 worker 长跑下 AI 面板首绘偶发超过默认 5s；放宽断言等待（不弱化断言）。
const expect = baseExpect.configure({ timeout: 15_000 })

/**
 * 票 03b：自然语言建议从高级页搬到规则页。模型用本机 HTTP 替身回答，
 * 断言只落在规则卡、`constraint_entry` 与保存审计这三处。
 */
test.describe.serial('排课规则：自然语言建议', () => {
  const suffix = Math.random().toString(36).slice(2, 8)
  // 「慕」是 e2e 全站唯一姓氏：spoken 的「X老师」按前缀解析教师，
  // 全量跑时其他 spec 也会建「王老师/张老师」等，重姓会让解析变成歧义候选。
  const teacherName = `慕明${suffix}`

  let server: Server
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed
  let reply = ''

  test.beforeAll(async () => {
    server = createServer((request, response) => {
      if (request.method !== 'POST' || !request.url?.includes('/chat/completions')) {
        response.writeHead(404)
        response.end()
        return
      }
      request.resume()
      request.on('end', () => {
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
    reply = JSON.stringify({
      proposals: [
        {
          sourceText: '慕老师周三下午不排课',
          type: 'TEACHER_UNAVAILABLE',
          strength: 'HARD',
          status: 'READY',
          explanation: '周三下午全部禁排',
          teacherName: '慕老师',
          days: [3],
          periods: [5, 6, 7, 8],
        },
      ],
    })
  })

  async function openPanel(page: Page) {
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()
    await page.getByTestId('rule-ai-toggle').click()
    await expect(page.getByTestId('constraint-ai-panel')).toBeVisible()
    await expect(page.getByTestId('constraint-ai-input')).toBeVisible()
  }

  async function generate(page: Page) {
    await page.getByTestId('constraint-ai-input').fill('慕老师周三下午不排课')
    await page.getByTestId('constraint-ai-generate').click()
    await expect(page.getByTestId('constraint-ai-run')).toBeVisible()
  }

  test('生成建议：按条展示，不写入生效配置', async ({ page }) => {
    const before = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })

    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await generate(page)

    await expect(page.getByTestId('constraint-ai-counts')).toContainText('1 条独立建议')
    const cards = page.getByTestId('constraint-proposal')
    await expect(cards).toHaveCount(1)
    await expect(cards.first().getByTestId('constraint-proposal-status')).toContainText('可加入')
    await expect(cards.first().getByTestId('constraint-proposal-entries').locator('li')).toHaveCount(4)
    // The rule page itself is untouched until the user adds a suggestion.
    await expect(page.getByTestId('rule-card')).toHaveCount(0)

    const after = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    expect(JSON.stringify(after)).toBe(JSON.stringify(before))
  })

  test('选中的建议加入规则卡，保存后落成约束条目并留下审计', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openPanel(page)
    await generate(page)

    await page.getByTestId('constraint-proposal-select').first().click()
    await page.getByTestId('constraint-ai-add').click()

    await expect(page.getByTestId('rule-ai-notice')).toContainText('已加入')
    const ruleCard = page.getByTestId('rule-card').filter({ hasText: '不上课' })
    await expect(ruleCard).toHaveCount(1)
    await expect(ruleCard).toContainText(teacherName)
    await expect(ruleCard).toContainText('周三第 5、6、7、8 节')
    // Adding a suggestion is still an unsaved change; the rule page save is the
    // only write path (and it carries the run/decisions audit fields).
    await expect(page.getByTestId('rule-save')).toBeEnabled()

    const saved = page.waitForResponse(
      (response) =>
        response.url().includes('/api/constraints') &&
        response.request().method() === 'PUT' &&
        response.status() === 200,
    )
    await page.getByTestId('rule-save').click()
    await saved
    await expect(page.getByTestId('rule-save')).toBeDisabled()

    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const unavailable = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    expect(unavailable.entries).toHaveLength(4)
    expect(new Set(unavailable.entries.map((entry: any) => entry.groupKey)).size).toBe(1)
    expect(unavailable.entries.every(
      (entry: any) => entry.params.teacherId && entry.params.dayOfWeek === 3,
    )).toBe(true)

    const audits = await apiCall(api, token, 'get', '/constraint-audits', undefined, {
      semesterId: seed.semesterId,
    })
    expect(audits.length).toBeGreaterThan(0)
    expect(audits[0].runId).toBeTruthy()
    expect(audits[0].decisions[0].action).toBe('ADDED')
    expect(audits[0].finalConfigs).toBeTruthy()
  })
})
