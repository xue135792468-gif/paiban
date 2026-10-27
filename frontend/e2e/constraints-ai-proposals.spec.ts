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

  test('生成只读建议：按条展示，不写入生效配置', async ({ page }) => {
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
    await expect(page.getByTestId('constraint-ai-note')).toContainText('只读建议')
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
