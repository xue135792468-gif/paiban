import fs from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'

/**
 * Where the phase-11 evidence screenshots land. They are deliberately outside the
 * repository, so a run never dirties the working tree; a machine that cannot write
 * there skips them instead of failing the suite.
 */
const DIRECTORY =
  process.env.PAIBAN_E2E_SCREENSHOTS ??
  (process.platform === 'win32' ? 'E:/paiban-wt/screenshots/phase11' : '')

/** Saves one full-page screenshot for a human to look at. Never an assertion. */
export async function capture(page: Page, name: string): Promise<void> {
  if (!DIRECTORY) return
  try {
    fs.mkdirSync(DIRECTORY, { recursive: true })
    // 过渡动画会让截图停在中途（例如 radio 的 checked 刚变、颜色还没到位），
    // 存下来的图看起来像业务错误。等动画结束再截稳定状态。
    await page.screenshot({
      path: path.join(DIRECTORY, `${name}.png`),
      fullPage: true,
      animations: 'disabled',
    })
  } catch {
    // Evidence is a courtesy: a locked or missing folder must not turn a green test red.
  }
}
