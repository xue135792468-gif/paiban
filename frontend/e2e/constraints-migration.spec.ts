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
 * 迁移前：预览计数来自后端真实扫描（本案 7 类已删除规则 + 2 类保留规则）。
 * 确认运行后：报告出现，7 类已删除规则在库里被停用但数据仍在；刷新页面后报告仍在，
 * 且配置版本已经推进（页面不会拿着旧版本去保存）。已删除的 7 类写不进公开 API
 * （规则页只认识 19 类），所以旧数据用 SQL 直接落库。
 */
test.describe.serial('约束配置：旧规则迁移卡片', () => {
  // 产品已删除、迁移时只停用的 7 类（ConstraintType.RETIRED）。
  const RETIRED = [
    'ROOM_CONFLICT',
    'ROOM_CAPACITY_FIT',
    'COURSE_REQUIRED_ROOM_TYPE',
    'COURSE_NOT_IN_EVENING',
    'EVENING_MAX',
    'TEACHER_MEETING_BLOCKED',
    'HEAD_TEACHER_FIRST_PERIOD',
  ]

  let api: APIRequestContext
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    seed = await seedConstraintScenario(api)
    // 开学时后端只为「教师/班级不冲突」建了两行；其余旧行按旧版 26 类落库。
    await runSql(
      api,
      'insert into constraint_config (semester_id, type, params_json, weight, hard, enabled) values '
        + RETIRED.map((type) => `(${seed.semesterId}, '${type}', '{}', 100, true, true)`).join(', '),
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

    // 预览：7 类停用、9 条类型配置，且还没运行过（无报告）。
    await expect(card(page)).toBeVisible()
    await expect(page.getByTestId('constraint-migration-counts')).toContainText('7 停用')
    await expect(page.getByTestId('constraint-migration-source')).toContainText('9 条类型配置')
    await expect(page.getByTestId('constraint-migration-report')).toHaveCount(0)

    await page.getByTestId('constraint-migration-run').click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '运行迁移' }).click()

    const report = page.getByTestId('constraint-migration-report')
    await expect(report).toBeVisible()
    await expect(page.getByTestId('constraint-migration-report-summary'))
      .toContainText('停用 7')
    // 逐条清单：9 条类型级规则，其中 7 条标注「停用」。
    const reportRows = page.getByTestId('constraint-migration-report-rows').locator('tbody tr')
    await expect(reportRows).toHaveCount(9)
    await expect(reportRows.filter({ hasText: '教室不冲突' }).first()).toContainText('停用')

    // 落库证据：7 类进入停用清单且参数仍可查；公开的可见配置只剩保留类。
    const migrationReport = await apiCall(api, token, 'get', '/constraint-migration/report', undefined, {
      semesterId: seed.semesterId,
    })
    const retiredRows = migrationReport.rows.filter(
      (row: any) => RETIRED.includes(row.oldType) && row.action === 'DISABLE',
    )
    expect(retiredRows).toHaveLength(7)
    expect(retiredRows.every((row: any) => typeof row.params === 'object')).toBe(true)
    const visible = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    expect(visible.some((item: any) => RETIRED.includes(item.type))).toBe(false)
    expect(visible.every((item: any) => item.enabled === true)).toBe(true)

    // 迁移推进了配置版本：页面与缓存不会再拿着旧版本判断「没有变化」。
    expect(await stateVersion(api, token)).toBeGreaterThan(versionBefore)

    // 刷新后报告仍在（报告是服务端最近一次运行，不是页面内存）。
    await page.reload()
    await expect(page.getByTestId('constraint-migration-report')).toBeVisible()
    await expect(page.getByTestId('constraint-migration-report-summary'))
      .toContainText('停用 7')
  })
})
