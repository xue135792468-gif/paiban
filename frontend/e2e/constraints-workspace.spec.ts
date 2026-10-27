import { expect, test, type APIRequestContext, type Browser, type Locator, type Page } from '@playwright/test'
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

/**
 * Ticket 14/11: unfinished work follows the account across devices, never crosses
 * accounts or semesters, has its own revision, and disappears after a successful save.
 */
test.describe.serial('约束配置：跨设备未保存工作', () => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const teacherName = `王明${suffix}`

  let server: Server
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed

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
              {
                message: {
                  role: 'assistant',
                  content: JSON.stringify({
                    proposals: [
                      {
                        sourceText: '王老师周三下午不排课',
                        type: 'TEACHER_UNAVAILABLE',
                        strength: 'HARD',
                        status: 'READY',
                        teacherName,
                        days: [3],
                        section: 'AFTERNOON',
                      },
                    ],
                  }),
                },
                finish_reason: 'stop',
              },
            ],
            usage: { prompt_tokens: 10, completion_tokens: 20 },
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
      employeeNo: `WS${suffix}`,
      subject: '语文',
    })
    const provider = await apiCall(api, token, 'post', '/ai-providers', {
      name: `工作区替身-${suffix}`,
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
    // Each test starts from a clean workspace and a known effective configuration.
    await apiCall(api, token, 'delete', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    await apiCall(api, token, 'put', `/constraints?semesterId=${seed.semesterId}&baseVersion=0`, {
      configs: [],
    })
  })

  test.afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()))
    await api.dispose()
  })

  async function openPage(browser: Browser): Promise<Page> {
    const context = await browser.newContext()
    const page = await context.newPage()
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    return page
  }

  async function pickOption(scope: Locator, testId: string, text: string) {
    await scope.getByTestId(testId).click()
    const option = scope
      .page()
      .locator('.el-select-dropdown:visible')
      .last()
      .locator('.el-select-dropdown__item')
      .filter({ hasText: new RegExp(`^${text}$`) })
      .first()
    await expect(option).toBeVisible()
    await option.click()
  }

  async function addManualCard(page: Page, teacher: string, day: string, period: string) {
    await page.getByTestId('constraint-entry-add').click()
    const card = page.getByTestId('constraint-entry').last()
    await pickOption(card, 'constraint-entry-teacher', teacher)
    const slot = card.getByTestId('constraint-entry-slot').first()
    await pickOption(slot, 'constraint-entry-slot-day', day)
    await pickOption(slot, 'constraint-entry-slot-period', `第${period}节`)
  }

  async function workspaceExists(): Promise<boolean> {
    const workspace = await apiCall(api, token, 'get', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    return workspace.exists === true
  }

  test('未保存工作跨设备恢复，保存后工作区消失', async ({ browser }) => {
    const pageA = await openPage(browser)
    await pageA.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await addManualCard(pageA, teacherName, '周一', '1')
    await pageA.getByTestId('constraint-ai-input').fill('王老师周三下午不排课')
    await pageA.getByTestId('constraint-ai-generate').click()
    await expect(pageA.getByTestId('constraint-ai-run')).toBeVisible()
    await expect.poll(workspaceExists, { timeout: 10000 }).toBe(true)
    await pageA.context().close()

    const pageB = await openPage(browser)
    await pageB.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await expect(pageB.getByTestId('constraint-entry')).toHaveCount(1)
    await expect(pageB.getByTestId('constraint-entry').first()).toContainText(teacherName)
    await expect(pageB.getByTestId('constraint-ai-input')).toHaveValue('王老师周三下午不排课')
    await expect(pageB.getByTestId('constraint-proposal')).toHaveCount(1)
    await expect(pageB.getByTestId('constraint-unsaved')).toBeVisible()

    await pageB.getByTestId('constraint-save').click()
    await expect(pageB.getByTestId('constraint-unsaved')).toHaveCount(0)
    await expect.poll(workspaceExists, { timeout: 10000 }).toBe(false)
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    expect(configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')).toBeTruthy()
    await pageB.context().close()
  })

  test('两台设备竞争时保留本地分支并显式选择', async ({ browser }) => {
    await apiCall(api, token, 'delete', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    const options = await apiCall(api, token, 'get', '/teachers/options')
    const firstId = options.find((item: any) => item.name === teacherName).id
    const otherTeacher = seed.teachers[1].name
    const otherId = options.find((item: any) => item.name === otherTeacher).id
    const state = await apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
    // Device A's autosave: one card owned by this account.
    await apiCall(api, token, 'put', '/constraint-workspace', {
      baseRevision: 0,
      baseVersion: state.version,
      payload: {
        text: '',
        configs: [
          {
            type: 'TEACHER_UNAVAILABLE',
            weight: 100,
            hard: true,
            enabled: true,
            entries: [
              { params: { teacherId: firstId, dayOfWeek: 1, periodNo: 1 }, groupKey: 'g-a' },
            ],
          },
        ],
        selectedProposalIds: [],
        decisions: [],
        isJunior: false,
      },
    }, { semesterId: seed.semesterId })

    const pageB = await openPage(browser)
    await pageB.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await expect(pageB.getByTestId('constraint-entry')).toHaveCount(1)
    await expect(pageB.getByTestId('constraint-unsaved')).toBeVisible()

    // Device A autosaves again while B is open: revision 1 -> 2.
    await apiCall(api, token, 'put', '/constraint-workspace', {
      baseRevision: 1,
      baseVersion: state.version,
      payload: {
        text: '另一台设备的新内容',
        configs: [
          {
            type: 'TEACHER_UNAVAILABLE',
            weight: 100,
            hard: true,
            enabled: true,
            entries: [
              { params: { teacherId: firstId, dayOfWeek: 1, periodNo: 1 }, groupKey: 'g-a' },
            ],
          },
        ],
        selectedProposalIds: [],
        decisions: [],
        isJunior: false,
      },
    }, { semesterId: seed.semesterId })

    await addManualCard(pageB, otherTeacher, '周二', '2')
    await expect(pageB.getByTestId('constraint-workspace-conflict')).toBeVisible()
    // B's local branch (the restored card plus its own) is untouched.
    await expect(
      pageB.getByTestId('constraint-entry').filter({ hasText: otherTeacher }),
    ).toHaveCount(1)

    await pageB.getByTestId('constraint-workspace-keep').click()
    await expect(pageB.getByTestId('constraint-workspace-conflict')).toHaveCount(0)
    await expect.poll(workspaceExists, { timeout: 10000 }).toBe(true)
    const workspace = await apiCall(api, token, 'get', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    expect(JSON.stringify(workspace.payload)).toContain(String(otherId))
    await pageB.context().close()
  })

  test('自动暂存失败时页面明确提示', async ({ browser }) => {
    await apiCall(api, token, 'delete', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    const page = await openPage(browser)
    await page.route('**/api/constraint-workspace?*', (route, request) => {
      if (request.method() === 'PUT') {
        void route.fulfill({ status: 500, body: '{"message":"服务器内部错误"}' })
        return
      }
      void route.continue()
    })
    await page.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await addManualCard(page, teacherName, '周五', '4')
    await expect(page.getByTestId('constraint-workspace-error')).toContainText('跨设备恢复尚未更新')
    await page.unroute('**/api/constraint-workspace?*')
    await page.context().close()
  })

  test('恢复后生效配置再次变化时仍走保存版本核对', async ({ browser }) => {
    await apiCall(api, token, 'delete', '/constraint-workspace', undefined, {
      semesterId: seed.semesterId,
    })
    const pageA = await openPage(browser)
    await pageA.locator('[data-type="TEACHER_UNAVAILABLE"]').click()
    await addManualCard(pageA, teacherName, '周三', '5')
    await expect.poll(workspaceExists, { timeout: 10000 }).toBe(true)
    await pageA.context().close()

    const pageB = await openPage(browser)
    // Wait until B has restored the draft, so its base version is the one we then change.
    await expect(pageB.getByTestId('constraint-unsaved')).toBeVisible()
    // While B is open, another writer changes the effective configuration.
    const state = await apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
    await apiCall(
      api,
      token,
      'put',
      `/constraints?semesterId=${seed.semesterId}&baseVersion=${state.version}`,
      {
        configs: [
          {
            type: 'TEACHER_CONFLICT',
            params: {},
            weight: 100,
            hard: true,
            enabled: true,
          },
        ],
      },
    )
    await pageB.getByTestId('constraint-save').click()
    await expect(pageB.getByTestId('constraint-conflict')).toBeVisible()
    await expect(pageB.getByTestId('constraint-unsaved')).toBeVisible()
    await pageB.context().close()
  })
})
