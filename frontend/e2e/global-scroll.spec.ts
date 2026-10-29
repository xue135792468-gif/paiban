import { expect, test, type Locator, type Page } from '@playwright/test'
import { apiCall, apiLogin, seedSchool, solveAndPublish, uiLogin } from './helpers'

/**
 * 票 08 验收：应用外壳固定为视口高度，左侧导航与右侧内容各自独立滚动。
 *
 * 断言只看可观察的滚动结果（scrollTop、元素是否在视口内），不断言组件内部状态。
 * 统一用 620px 高的视口：真实菜单（约 20 项）和 40 行表格都会溢出，
 * 这样无需特殊构造就能验证「长菜单 + 长内容」两个滚动区。
 */
test.use({ viewport: { width: 1280, height: 620 } })

interface ScrollState {
  /** 窗口（document）已滚动的距离；全站修复后应恒为 0。 */
  windowY: number
  /** 窗口是否出现整页滚动条。 */
  windowScrollable: boolean
  /** 右侧内容区（el-main）scrollTop。 */
  mainTop: number
  /** 右侧内容区自身是否可滚。 */
  mainScrollable: boolean
  /** 左侧菜单 scrollTop。 */
  menuTop: number
  /** 左侧菜单自身是否可滚。 */
  menuScrollable: boolean
}

async function readScroll(page: Page): Promise<ScrollState> {
  return page.evaluate(() => {
    const doc = document.scrollingElement ?? document.documentElement
    const main = document.querySelector<HTMLElement>('[data-testid="layout-main"]')
    const menu = document.querySelector<HTMLElement>('[data-testid="layout-menu"]')
    return {
      windowY: doc.scrollTop,
      windowScrollable: doc.scrollHeight > doc.clientHeight + 1,
      mainTop: main?.scrollTop ?? -1,
      mainScrollable: !!main && main.scrollHeight > main.clientHeight + 1,
      menuTop: menu?.scrollTop ?? -1,
      menuScrollable: !!menu && menu.scrollHeight > menu.clientHeight + 1,
    }
  })
}

/** 把鼠标移到目标区域上再滚轮，走真实用户路径而不是直接改 scrollTop。 */
async function wheelOn(
  page: Page,
  target: Locator,
  deltaY: number,
  position?: { x: number; y: number },
): Promise<void> {
  await target.hover({ position })
  await page.mouse.wheel(0, deltaY)
}

/**
 * 滚轮的滚动由浏览器异步应用，紧接着的同步读取可能拿到旧值。
 * 用 waitForFunction 等到目标滚动容器的 scrollTop 真正越过阈值再读数。
 */
async function waitForScroll(page: Page, key: 'main' | 'menu', min: number): Promise<void> {
  await page.waitForFunction(
    ([testId, threshold]) => {
      const el = document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
      return !!el && el.scrollTop >= threshold
    },
    [key === 'main' ? 'layout-main' : 'layout-menu', min] as const,
    { timeout: 5_000 },
  )
}

/** 通过公开 API 造一批教师，把教师页撑成长内容。返回最后一个名字。 */
async function seedManyTeachers(
  request: Parameters<typeof apiLogin>[0],
  count = 40,
): Promise<string> {
  const token = await apiLogin(request)
  const suffix = Math.random().toString(36).slice(2, 8)
  let lastName = ''
  for (let index = 1; index <= count; index++) {
    const name = `滚动教师${suffix}${String(index).padStart(2, '0')}`
    await apiCall(request, token, 'post', '/teachers', {
      name,
      employeeNo: `GS-${suffix}-${index}`,
    })
    lastName = name
  }
  return lastName
}

/** 通过公开 API 造一批教室，把教室页撑成长内容。返回最后一个名字。 */
async function seedManyRooms(
  request: Parameters<typeof apiLogin>[0],
  count = 40,
): Promise<string> {
  const token = await apiLogin(request)
  const suffix = Math.random().toString(36).slice(2, 8)
  let lastName = ''
  for (let index = 1; index <= count; index++) {
    const name = `滚动教室${suffix}${String(index).padStart(2, '0')}`
    await apiCall(request, token, 'post', '/rooms', {
      name,
      capacity: 40,
      type: 'NORMAL',
    })
    lastName = name
  }
  return lastName
}

test('长内容页面不出整页滚动条，顶部栏与导航标识保持可见，底部可到达', async ({
  page,
  request,
}) => {
  const lastName = await seedManyTeachers(request)
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/teachers')
  // e2e 库跨测试累积，用本批唯一名字确认表格已渲染，不锁精确行数。
  await expect(page.getByText(lastName).first()).toBeAttached()

  const before = await readScroll(page)
  expect(before.windowScrollable).toBe(false)
  expect(before.windowY).toBe(0)
  expect(before.mainScrollable).toBe(true)

  const header = page.getByTestId('layout-header')
  const logo = page.getByTestId('layout-logo')
  await expect(header).toBeVisible()
  await expect(logo).toBeInViewport()

  await wheelOn(page, page.getByTestId('layout-main'), 5000)
  await waitForScroll(page, 'main', 1)
  const after = await readScroll(page)
  expect(after.mainTop).toBeGreaterThan(0)
  expect(after.windowY).toBe(0)
  expect(after.windowScrollable).toBe(false)
  await expect(header).toBeVisible()
  await expect(logo).toBeInViewport()
  // 长内容底部（最后一行）可正常访问
  await expect(page.locator('.el-table__row').last()).toBeInViewport()
})

test('内容短于视口时同样不出整页滚动条', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-providers')

  const state = await readScroll(page)
  expect(state.windowScrollable).toBe(false)
  expect(state.windowY).toBe(0)
  expect(state.mainScrollable).toBe(false)
  await expect(page.getByTestId('layout-header')).toBeVisible()
  await expect(page.getByTestId('layout-logo')).toBeInViewport()
})

test('导航区与内容区分别滚动，互不带动', async ({ page, request }) => {
  const lastName = await seedManyTeachers(request)
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/teachers')
  await expect(page.getByText(lastName).first()).toBeAttached()

  // 只滚内容：菜单与窗口不动
  await wheelOn(page, page.getByTestId('layout-main'), 1200)
  await waitForScroll(page, 'main', 1)
  const afterMain = await readScroll(page)
  expect(afterMain.mainTop).toBeGreaterThan(0)
  expect(afterMain.menuTop).toBe(0)
  expect(afterMain.windowY).toBe(0)

  // 只滚菜单：内容与窗口不动
  await wheelOn(page, page.getByTestId('layout-aside'), 1200)
  await waitForScroll(page, 'menu', 1)
  const afterMenu = await readScroll(page)
  expect(afterMenu.menuTop).toBeGreaterThan(0)
  expect(afterMenu.mainTop).toBe(afterMain.mainTop)
  expect(afterMenu.windowY).toBe(0)

  // 再滚内容：菜单仍然不动
  await wheelOn(page, page.getByTestId('layout-main'), 600)
  await waitForScroll(page, 'main', afterMain.mainTop + 1)
  const final = await readScroll(page)
  expect(final.mainTop).toBeGreaterThan(afterMain.mainTop)
  expect(final.menuTop).toBe(afterMenu.menuTop)
})

test('切换菜单页后内容回到顶部，左侧菜单滚动位置保持', async ({ page, request }) => {
  const lastTeacher = await seedManyTeachers(request)
  const lastRoom = await seedManyRooms(request)
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/teachers')
  await expect(page.getByText(lastTeacher).first()).toBeAttached()

  // 菜单滚到中段（150px，保证「教室」项完整可见，点击不会触发自动滚动）
  await wheelOn(page, page.getByTestId('layout-aside'), 150)
  await waitForScroll(page, 'menu', 1)
  const menuState = await readScroll(page)
  expect(menuState.menuTop).toBeGreaterThan(0)
  const roomsItem = page.getByRole('menuitem', { name: '教室' })
  await expect(roomsItem).toBeInViewport()

  // 内容滚到底部
  await wheelOn(page, page.getByTestId('layout-main'), 5000)
  await waitForScroll(page, 'main', 1)
  expect((await readScroll(page)).mainTop).toBeGreaterThan(0)

  await roomsItem.click()
  await page.waitForURL(/\/admin\/rooms$/)
  await expect(page.getByText(lastRoom).first()).toBeAttached()

  const afterNav = await readScroll(page)
  expect(afterNav.mainTop).toBe(0)
  expect(afterNav.menuTop).toBe(menuState.menuTop)
})

test('长菜单的首尾菜单项均可到达', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/teachers')

  const aside = page.getByTestId('layout-aside')
  // name 默认按子串匹配；「调代工作台」会让非精确的「工作台」命中两项。
  const first = page.getByRole('menuitem', { name: '工作台', exact: true })
  const last = page.getByRole('menuitem', { name: '帮助中心', exact: true })

  // 菜单项是异步渲染的：先等首尾都出现，再量滚动高度，否则早量会误判不可滚。
  await expect(first).toBeVisible()
  await expect(last).toBeAttached()
  await expect
    .poll(() => readScroll(page).then((state) => state.menuScrollable))
    .toBe(true)
  await expect(first).toBeInViewport()

  await wheelOn(page, aside, 5000)
  await expect(last).toBeInViewport()
  await expect(first).not.toBeInViewport()

  await wheelOn(page, aside, -5000)
  await expect(first).toBeInViewport()
})

test('帮助页锚点定位到目标内容且保持可见', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/help/foundation#teachers')
  await expect(page.getByTestId('help-doc-title')).toHaveText('基础数据')

  // 锚点之后的小节正文进入视口（锚点本身是空元素，用它后面的正文做可观察断言）
  await expect(page.getByText('教师本身不能登录系统')).toBeInViewport()
  const windowY = await page.evaluate(
    () => document.scrollingElement?.scrollTop ?? document.documentElement.scrollTop,
  )
  expect(windowY).toBeGreaterThan(0)
})

test.describe('弹窗独立滚动', () => {
  // 480px 高的视口保证「新建教师」弹窗内容超出视口，必须内部滚动才能到底部。
  test.use({ viewport: { width: 1280, height: 480 } })

  test('弹窗长内容独立滚动，不带动导航与主内容', async ({ page, request }) => {
    const lastName = await seedManyTeachers(request)
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/teachers')
    await expect(page.getByText(lastName).first()).toBeAttached()

    await page.getByRole('button', { name: '新建教师' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const before = await readScroll(page)
    await dialog.hover({ position: { x: 250, y: 180 } })
    await page.mouse.wheel(0, 900)

    const after = await readScroll(page)
    expect(after.mainTop).toBe(before.mainTop)
    expect(after.menuTop).toBe(before.menuTop)
    expect(after.windowY).toBe(0)
    // 滚动后弹窗底部操作按钮可达
    await expect(dialog.getByRole('button', { name: '保存' })).toBeInViewport()
  })
})

test.describe('同页筛选不回顶', () => {
  test('课表页切换视图页签与视角不回顶', async ({ page, request }) => {
    const seed = await seedSchool(request)
    await solveAndPublish(request, seed)
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/timetable')
    // 行政班/教师/教室视角是普通网格（只有全校视角才用虚拟表格），等格子渲染即可。
    await expect(page.locator('.grid').first()).toBeVisible()

    // 内容高度/滚动能力在渲染完成前读数会偏低：轮询到可滚状态再继续。
    await expect
      .poll(async () => (await readScroll(page)).mainScrollable)
      .toBe(true)

    // 内容区轻微下滚：只滚 15px（内容起始 padding 内），保证换行工具栏里
    // 两组 radio 都保持可见，后续点击不会触发自动滚动改变基线。
    await wheelOn(page, page.getByTestId('layout-main'), 15, { x: 300, y: 30 })
    await waitForScroll(page, 'main', 1)
    await expect.poll(async () => (await readScroll(page)).mainTop).toBeGreaterThan(0)
    const scrolled = await readScroll(page)

    // 切「原始」页签：同一页面的查询状态变化，不应回顶
    const baselineRadio = page.locator('.el-radio-button', { hasText: '原始' })
    await expect(baselineRadio).toBeInViewport()
    await baselineRadio.click()
    await expect(baselineRadio).toHaveClass(/is-active/)
    await expect(async () => {
      const current = await readScroll(page)
      expect(current.mainTop).toBe(scrolled.mainTop)
    }).toPass({ timeout: 5_000 })

    // 切「教师」视角：同样不回顶
    const teacherRadio = page.locator('.el-radio-button', { hasText: '教师' })
    await expect(teacherRadio).toBeInViewport()
    await teacherRadio.click()
    await expect(teacherRadio).toHaveClass(/is-active/)
    await expect(async () => {
      const current = await readScroll(page)
      expect(current.mainTop).toBe(scrolled.mainTop)
    }).toPass({ timeout: 5_000 })
  })
})
