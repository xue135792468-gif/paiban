import { expect, test, type Locator, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  openSwapFlow,
  pickTargetAndGenerate,
  seedSwapWorkbenchScenario,
} from './helpers'

/**
 * 票 06：已发布课表拖拽进入调课预览。
 * 拖动只表达「调整到哪里」的意图：松手即带上目标日期/课节进入票 05 已有的
 * 候选 → 整环对照 → 审批/确认流程；无效目标给出明确原因且零写入；
 * 表单入口保持等价。教师视角发起时目标网格锁定源课行政班。
 */

/** 按本地时区平移日期（e2e 种子日期都是未来某周的周一~周五）。 */
function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00`)
  value.setDate(value.getDate() + days)
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
}

/** 用 Playwright 真实鼠标管线驱动指针拖拽：按住来源课 → 移到目标格 → 松手。 */
async function dragSourceToCell(page: Page, target: Locator): Promise<void> {
  const source = page.getByTestId('swap-drag-source')
  await source.scrollIntoViewIfNeeded()
  await target.scrollIntoViewIfNeeded()
  const from = await source.boundingBox()
  const to = await target.boundingBox()
  expect(from).toBeTruthy()
  expect(to).toBeTruthy()
  await page.mouse.move(from!.x + from!.width / 2, from!.y + from!.height / 2)
  await page.mouse.down()
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2, { steps: 10 })
  await page.mouse.up()
}

test('拖拽到同班有课的目标格：显示目标日期/课节并进入候选与整环对照', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)

  await openSwapFlow(page, seed)

  // 教师视角锁定：拖拽网格是源课行政班的周课表——源教师周五没课，
  // 班里周五却有数学课；教师空闲格不在网格里，不会误当班级目标槽。
  const grid = page.getByTestId('swap-target-grid')
  await expect(grid).toBeVisible()
  await expect(grid).toContainText('数学')
  // 教师自己的周课表不是拖拽面：没有任何投放格。
  await expect(page.getByTestId('workbench-timetable').locator('[data-swap-cell]')).toHaveCount(0)

  const targetCell = page.getByTestId(`swap-cell-${seed.targetDate}-1`)
  await expect(targetCell).toHaveAttribute('data-cell-state', 'valid')
  await dragSourceToCell(page, targetCell)

  // 松手即带上目标日期/课节进入候选（与表单完全相同的状态与路径）。
  await expect(page.getByTestId('swap-target-date').locator('input')).toHaveValue(seed.targetDate)
  await expect(page.getByTestId('swap-target-period')).toContainText('数学')
  await expect(page.getByTestId('swap-plan-card').first()).toContainText('2 人环')

  // 选定候选 → 整环左右对照：来源日与目标日各一块，变化清单两步。
  await page.getByTestId('choose-swap-plan-0').click()
  await expect(page.getByTestId('swap-compare')).toBeVisible()
  await expect(page.getByTestId(`swap-left-${seed.lessonDate}`)).toBeVisible()
  await expect(page.getByTestId(`swap-right-${seed.targetDate}`)).toBeVisible()
  await expect(page.getByTestId('swap-change-list').locator('.el-table__row')).toHaveCount(2)

  // 小屏：左右面板与清单逐一可见，调整前/后标签始终可辨。
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('swap-compare')).toBeVisible()
  await expect(page.getByText('调整前（最新）').first()).toBeVisible()
  await expect(page.getByText('调整后（方案）').first()).toBeVisible()
  await expect(page.getByTestId('swap-change-list')).toBeVisible()
})

test('拖到空格与窗口外：显示明确不可用原因，不产生候选或写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)
  await openSwapFlow(page, seed)

  // 空格：周四第 1 节本班没课（swap-targets 返回空槽位，与表单空日同一判定）。
  const thursday = shiftDate(seed.lessonDate, 1)
  const emptyCell = page.getByTestId(`swap-cell-${thursday}-1`)
  await expect(emptyCell).toHaveAttribute('data-cell-state', 'empty')
  await dragSourceToCell(page, emptyCell)
  await expect(page.getByTestId('swap-drop-invalid')).toBeVisible()
  await expect(page.getByTestId('swap-drop-invalid')).toContainText('空格')
  await expect(page.getByTestId('swap-plan-card')).toHaveCount(0)

  // 窗口外：周一早于来源日期（表单日期选择器同样禁用这一天）。
  const monday = shiftDate(seed.lessonDate, -2)
  const beforeCell = page.getByTestId(`swap-cell-${monday}-1`)
  await expect(beforeCell).toHaveAttribute('data-cell-state', 'before-source')
  await dragSourceToCell(page, beforeCell)
  await expect(page.getByTestId('swap-drop-invalid')).toContainText('早于来源日期')
  await expect(page.getByTestId('swap-plan-card')).toHaveCount(0)

  // API 侧不变量：拖动零写入——没有申请，也没有生效行。
  const token = await apiLogin(api)
  const pending = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'PENDING_APPROVAL',
  })
  expect(pending.length).toBe(0)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(0)
})

test('取消预览后零变化：无申请、无生效、当前有效课表不变', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)
  await openSwapFlow(page, seed)

  const targetCell = page.getByTestId(`swap-cell-${seed.targetDate}-1`)
  await expect(targetCell).toHaveAttribute('data-cell-state', 'valid')
  await dragSourceToCell(page, targetCell)
  await expect(page.getByTestId('swap-plan-card').first()).toBeVisible()
  await page.getByTestId('choose-swap-plan-0').click()
  await expect(page.getByTestId('swap-compare')).toBeVisible()

  await page.getByTestId('swap-cancel-preview').click()
  // 取消后回到流程起点：预览合同保证零申请、零生效、零通知。
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()

  const token = await apiLogin(api)
  const pending = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'PENDING_APPROVAL',
  })
  expect(pending.length).toBe(0)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(0)

  // 当前有效安排不变：来源课仍在本班周三第 1 节。
  const entries = await apiCall(api, token, 'get', `/timetable/class/${seed.classId}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  const chinese = entries.find((row: any) => row.courseName === seed.courseChineseName)
  expect(chinese).toBeTruthy()
  expect(chinese.dayOfWeek).toBe(3)
  expect(chinese.periodNo).toBe(1)
})

test('表单与拖拽等价：同一目标得到同一批候选', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)

  // 表单路径：明确目标日期（周五）+ 目标课节（数学）。
  await openSwapFlow(page, seed)
  await pickTargetAndGenerate(page, seed)
  await expect(page.getByTestId('swap-plan-card').first()).toBeVisible()
  const formCount = await page.getByTestId('swap-plan-card').count()
  const formFirst = await page.getByTestId('swap-plan-card').first().innerText()

  await page.getByTestId('swap-cancel').click()

  // 拖拽路径：拖到同一个目标格。
  await openSwapFlow(page, seed)
  const targetCell = page.getByTestId(`swap-cell-${seed.targetDate}-1`)
  await expect(targetCell).toHaveAttribute('data-cell-state', 'valid')
  await dragSourceToCell(page, targetCell)
  await expect(page.getByTestId('swap-plan-card').first()).toBeVisible()

  expect(await page.getByTestId('swap-plan-card').count()).toBe(formCount)
  expect(await page.getByTestId('swap-plan-card').first().innerText()).toBe(formFirst)
})
