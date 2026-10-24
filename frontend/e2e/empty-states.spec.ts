import { expect, test, type Page } from '@playwright/test'
import { uiLogin } from './helpers'

/**
 * These tests never rely on what the shared e2e database happens to contain:
 * every list endpoint is intercepted, and the semester endpoints are stubbed
 * so both branches (no semester / semester with empty lists) are deterministic.
 * Nothing is seeded, so other specs keep their own preconditions.
 */

const SEMESTER = { id: 9001, name: 'E2E 空状态学期', current: true }

async function stubJson(page: Page, pattern: string, body: unknown) {
  await page.route(pattern, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    }),
  )
}

async function stubNoSemester(page: Page) {
  await page.route('**/api/semesters/current', (route) =>
    route.fulfill({ status: 204, body: '' }),
  )
  await stubJson(page, '**/api/semesters', [])
  await stubJson(page, '**/api/semesters/*/periods', [])
}

async function stubSemester(page: Page) {
  await stubJson(page, '**/api/semesters/current', SEMESTER)
  await stubJson(page, '**/api/semesters', [SEMESTER])
}

async function stubEmptyFoundation(page: Page) {
  for (const path of ['grades', 'classes', 'teachers', 'rooms', 'courses', 'assignments']) {
    await stubJson(page, `**/api/${path}**`, [])
  }
}

function guide(page: Page) {
  // Tab panes keep inactive content mounted, so only the visible guide counts.
  return page.locator('[data-testid="empty-state-guide"]:visible')
}

async function expectGuide(
  page: Page,
  options: { title: string; button?: string; href: string },
) {
  const root = guide(page)
  await expect(root).toBeVisible()
  await expect(root.getByRole('heading', { name: options.title })).toBeVisible()
  if (options.button) {
    await expect(root.getByRole('button', { name: options.button })).toBeVisible()
  }
  await expect(root.getByRole('link', { name: '查看帮助文档' })).toHaveAttribute(
    'href',
    options.href,
  )
  return root
}

test('基础数据与授课任务列表为空时显示引导', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await stubSemester(page)
  await stubEmptyFoundation(page)

  await page.goto('/admin/grades')
  let root = await expectGuide(page, {
    title: '还没有年级数据',
    button: '新建年级',
    href: '/help/foundation#grades',
  })
  await root.getByRole('button', { name: '新建年级' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建年级')
  await page.keyboard.press('Escape')

  await page.goto('/admin/classes')
  root = await expectGuide(page, {
    title: '还没有行政班数据',
    button: '新建行政班',
    href: '/help/foundation#classes',
  })
  await root.getByRole('button', { name: '新建行政班' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建行政班')
  await page.keyboard.press('Escape')

  await page.goto('/admin/teachers')
  root = await expectGuide(page, {
    title: '还没有教师数据',
    button: '新建教师',
    href: '/help/foundation#teachers',
  })
  await root.getByRole('button', { name: '新建教师' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建教师')
  await page.keyboard.press('Escape')

  await page.goto('/admin/rooms')
  root = await expectGuide(page, {
    title: '还没有教室数据',
    button: '新建教室',
    href: '/help/foundation#rooms',
  })
  await root.getByRole('button', { name: '新建教室' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建教室')
  await page.keyboard.press('Escape')

  await page.goto('/admin/courses')
  root = await expectGuide(page, {
    title: '还没有课程数据',
    button: '新建课程',
    href: '/help/foundation#courses',
  })
  await root.getByRole('button', { name: '新建课程' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建课程')
  await page.keyboard.press('Escape')

  await page.goto('/admin/assignments')
  root = await expectGuide(page, {
    title: '还没有授课任务',
    button: '新建授课任务',
    href: '/help/scheduling#assignments',
  })
  await root.getByRole('button', { name: '新建授课任务' }).click()
  await expect(page.locator('.el-dialog:visible')).toContainText('新建授课任务')
})

test('无学期时引导去学期页', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await stubNoSemester(page)
  await stubEmptyFoundation(page)

  const cases = [
    { path: '/admin/grades', href: '/help/foundation#grades' },
    { path: '/admin/classes', href: '/help/foundation#classes' },
    { path: '/admin/assignments', href: '/help/scheduling#assignments' },
    { path: '/admin/constraints', href: '/help/scheduling#constraints' },
    { path: '/admin/scheduling', href: '/help/scheduling#run' },
  ]

  for (const item of cases) {
    await page.goto(item.path)
    const root = await expectGuide(page, {
      title: '还没有学期',
      button: '去创建学期',
      href: item.href,
    })
    await root.getByRole('button', { name: '去创建学期' }).click()
    await expect(page).toHaveURL(/\/admin\/semesters$/)
  }
})

test('排课草稿、排课历史与约束列表为空时显示引导', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await stubSemester(page)
  await stubEmptyFoundation(page)
  await stubJson(page, '**/api/timetable-drafts**', [])
  await stubJson(page, '**/api/scheduling-jobs**', [])
  await stubJson(page, '**/api/constraint-types', [])
  await stubJson(page, '**/api/constraints**', [])

  await page.goto('/admin/scheduling')
  for (const item of [
    { title: '还没有课表草稿', href: '/help/scheduling#draft' },
    { title: '还没有排课历史', href: '/help/scheduling#run' },
  ]) {
    const root = page
      .locator('[data-testid="empty-state-guide"]:visible')
      .filter({ has: page.getByRole('heading', { name: item.title }) })
    await expect(root).toHaveCount(1)
    await expect(root.getByRole('link', { name: '查看帮助文档' })).toHaveAttribute(
      'href',
      item.href,
    )
  }

  await page.goto('/admin/constraints')
  const constraints = page
    .locator('[data-testid="empty-state-guide"]:visible')
    .filter({ has: page.getByRole('heading', { name: '还没有可配置的约束' }) })
  await expect(constraints).toHaveCount(1)
  await expect(constraints.getByRole('link', { name: '查看帮助文档' })).toHaveAttribute(
    'href',
    '/help/scheduling#constraints',
  )
})

test('调代待审批与调代记录为空时显示引导', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await stubSemester(page)
  await stubJson(page, '**/api/substitution-requests**', [])
  await stubJson(page, '**/api/substitutions**', [])
  await stubJson(page, '**/api/classes**', [])
  await stubJson(page, '**/api/teachers**', [])

  await page.goto('/substitutions')

  // 默认的「我的调代」页签：我的申请为空时也要有引导。
  await expectGuide(page, {
    title: '还没有调代申请',
    href: '/help/substitution#apply',
  })

  await page.getByRole('tab', { name: '待审批' }).click()
  const pending = await expectGuide(page, {
    title: '没有待审批的申请',
    href: '/help/substitution#approve',
  })
  await expect(pending.getByRole('button')).toHaveCount(0)

  await page.getByRole('tab', { name: '调代记录' }).click()
  await expectGuide(page, {
    title: '还没有调代记录',
    href: '/help/substitution#records',
  })
})

test('课表无发布数据时引导去自动排课', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await stubNoSemester(page)
  await stubEmptyFoundation(page)

  await page.goto('/timetable')
  const root = await expectGuide(page, {
    title: '暂无课表数据',
    button: '去自动排课',
    href: '/help/timetable#views',
  })
  await root.getByRole('button', { name: '去自动排课' }).click()
  await expect(page).toHaveURL(/\/admin\/scheduling$/)
})
