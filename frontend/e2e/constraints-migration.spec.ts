import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  runSql,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'

/**
 * 票 11：旧规则迁移卡片（/rules/advanced 顶部）的用户可见流程。
 *
 * 迁移前：预览计数来自后端真实扫描（本案 2 类保留的开关 + 若干产品已不认识的旧类型）。
 * 确认运行后：报告出现；产品已删的旧类型无法自动映射，只标「待确认」、数据原样保留；
 * 刷新页面后报告仍在，且配置版本已经推进。旧数据用 SQL 直接落库（公开 API 只认识
 * 当前目录里的 18 类）。
 */
test.describe.serial('约束配置：旧规则迁移卡片', () => {
  // 产品已删除、目录里已不存在的旧类型编码：后端对未知编码一律 REVIEW，不停用。
  const UNKNOWN = [
    'LEGACY_RESOURCE_A',
    'LEGACY_RESOURCE_B',
    'LEGACY_TIMETABLE_RULE',
  ]

  let api: APIRequestContext
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    seed = await seedConstraintScenario(api)
    // 开学时后端只为「教师/班级不冲突」建了两行；其余旧行按旧版配置落库。
    await runSql(
      api,
      'insert into constraint_config (semester_id, type, params_json, weight, hard, enabled) values '
        + UNKNOWN.map((type) => `(${seed.semesterId}, '${type}', '{}', 100, true, true)`).join(', '),
    )
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  function card(page: Page) {
    return page.getByTestId('constraint-migration-card')
  }

  async function stateVersion(context: APIRequestContext, token: string) {
    const state = await apiCall(context, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
    return state.version as number
  }

  test('预览计数 → 确认运行 → 报告留存', async ({ page }) => {
    const token = await apiLogin(api)
    const versionBefore = await stateVersion(api, token)

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules/advanced')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()

    // 预览：2 条保留 + 3 条待确认，5 条类型配置，且还没运行过（无报告）。
    await expect(card(page)).toBeVisible()
    await expect(page.getByTestId('constraint-migration-counts')).toContainText('3 待确认')
    await expect(page.getByTestId('constraint-migration-source')).toContainText('5 条类型配置')
    await expect(page.getByTestId('constraint-migration-report')).toHaveCount(0)

    await page.getByTestId('constraint-migration-run').click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '运行迁移' }).click()

    const report = page.getByTestId('constraint-migration-report')
    await expect(report).toBeVisible()
    await expect(page.getByTestId('constraint-migration-report-summary'))
      .toContainText('待确认 3')
    // 逐条清单：5 条类型级规则，其中 3 条无法自动映射。
    const reportRows = page.getByTestId('constraint-migration-report-rows').locator('tbody tr')
    await expect(reportRows).toHaveCount(5)
    await expect(reportRows.filter({ hasText: 'LEGACY_RESOURCE_A' }).first()).toContainText('待确认')

    // 落库证据：未知编码只标记 REVIEW、参数仍可查、行仍启用；公开的可见配置只剩保留类。
    const migrationReport = await apiCall(api, token, 'get', '/constraint-migration/report', undefined, {
      semesterId: seed.semesterId,
    })
    const reviewRows = migrationReport.rows.filter(
      (row: any) => UNKNOWN.includes(row.oldType) && row.action === 'REVIEW',
    )
    expect(reviewRows).toHaveLength(3)
    expect(reviewRows.every((row: any) => typeof row.params === 'object')).toBe(true)
    const visible = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    expect(visible.some((item: any) => UNKNOWN.includes(item.type))).toBe(false)
    expect(visible.every((item: any) => item.enabled === true)).toBe(true)

    // 迁移推进了配置版本：页面与缓存不会再拿着旧版本判断「没有变化」。
    expect(await stateVersion(api, token)).toBeGreaterThan(versionBefore)

    // 刷新后报告仍在（报告是服务端最近一次运行，不是页面内存）。
    await page.reload()
    await expect(page.getByTestId('constraint-migration-report')).toBeVisible()
    await expect(page.getByTestId('constraint-migration-report-summary'))
      .toContainText('待确认 3')
  })
})
