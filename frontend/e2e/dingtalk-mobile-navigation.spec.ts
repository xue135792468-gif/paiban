import { expect, test } from '@playwright/test'
import {
  DINGTALK_UA,
  apiCall,
  apiLogin,
  installFakeWindowDd,
  newContext,
  trackBrowserErrors,
} from './helpers'
import { capture } from './screenshots'

test('手机免登默认本人课表后，可通过可见导航往返我的调代且不展示管理入口', async ({ browser }) => {
  const api = await newContext()
  const superAdmin = await apiLogin(api)
  const caseId = `nav_${Math.random().toString(36).slice(2, 10)}`
  let accountId: number | undefined
  const phone = await browser.newContext({
    userAgent: DINGTALK_UA,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  })
  try {
    await apiCall(api, superAdmin, 'post', '/teachers', {
      name: `验收申请人-${caseId}`,
      employeeNo: `ACC-${caseId}`,
    })
    await apiCall(api, superAdmin, 'put', '/integrations/dingtalk/config', {
      enabled: true,
      appKey: 'mobile-nav-key',
      appSecret: 'mobile-nav-secret',
      agentId: '1000',
      corpId: 'mobile-nav-corp',
      syncStrategy: 'local',
    })
    await installFakeWindowDd(phone, `fake:acceptance:${caseId}`)
    const page = await phone.newPage()
    const errors = trackBrowserErrors(page)
    const authPromise = page.waitForResponse((response) =>
      response.request().method() === 'POST' && response.url().endsWith('/api/auth/dingtalk'))
    await page.goto('/login')
    const authResponse = await authPromise
    expect(authResponse.status()).toBe(200)
    const auth = await authResponse.json()
    accountId = auth.user.id
    expect(auth.user.roles).toEqual(['teacher'])
    await expect(page).toHaveURL(/\/workbench\/timetable(?:\?.*)?$/)
    await expect(page.getByTestId('layout-aside')).toBeHidden()
    await capture(page, 'whole-mobile-navigation-landing')
    const toggle = page.getByRole('button', { name: '打开导航', exact: true })
    await expect(toggle).toBeVisible()
    await toggle.click()
    const drawer = page.getByRole('dialog', { name: '导航', exact: true })
    await expect(drawer).toBeVisible()
    for (const name of ['设置', '数据管理', '排课规则', 'AI 助手']) {
      await expect(drawer.getByRole('menuitem', { name, exact: true })).toHaveCount(0)
    }
    await drawer.getByRole('menuitem', { name: '调代课', exact: true }).click()
    await expect(page).toHaveURL(/\/substitution\/requests(?:\?.*)?$/)
    await expect(drawer).toBeHidden()
    await capture(page, 'whole-mobile-navigation-to-requests')
    await toggle.click()
    await drawer.getByRole('menuitem', { name: '课表工作台', exact: true }).click()
    await expect(page).toHaveURL(/\/workbench\/timetable(?:\?.*)?$/)
    await expect(drawer).toBeHidden()
    expect(errors).toEqual([])
  } finally {
    await phone.close()
    try {
      if (accountId != null) await apiCall(api, superAdmin, 'post', `/users/${accountId}/disable`)
    } finally {
      try {
        await apiCall(api, superAdmin, 'put', '/integrations/dingtalk/config', { enabled: false })
      } finally {
        await api.dispose()
      }
    }
  }
})
