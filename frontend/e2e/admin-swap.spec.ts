import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  openWorkbenchAtSourceLesson,
  pickSwapTargetAndContinue,
  seedSwapWorkbenchScenario,
  startSwapAction,
} from './helpers'

/**
 * 票 05：管理员同班调课（三步向导版）。管理员在工作台为实际授课实例明确指定
 * 目标日期（窗口 chip）与课节（radio），第③步生成闭合置换环候选（页签），
 * 检查变化格与折叠明细后亲自确认生效。
 * 公共流程（openWorkbenchAtSourceLesson / startSwapAction / pickSwapTargetAndContinue）
 * 在 helpers.ts，与拖拽 spec 共用。
 */

test('管理员调课：亲自确认整环生效，课表按实际日期呈现', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)

  await openWorkbenchAtSourceLesson(page, { teacherName: seed.teacherChineseName, lessonDate: seed.lessonDate })
  await startSwapAction(page)
  await pickSwapTargetAndContinue(page, seed)
  await expect(page.getByTestId('change-week-0')).toBeVisible()

  await page.getByTestId('personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已生效')
  await expect(page.getByTestId('workbench-result')).toContainText('课表已更新')

  // 生效事实（API 侧）：两条 SWAP 生效行，实例日期成对（周三↔周五）。
  const token = await apiLogin(api)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(2)
  const dates = effective
    .map((row: any) => `${row.lessonDate}|${row.toDayOfWeek}`)
    .sort()
  expect(dates).toEqual([`${seed.lessonDate}|5`, `${seed.targetDate}|3`].sort())

  // 班级课表（周五视角）：语文课出现在周五第 1 节并带「调」标记。
  const fridayEntries = await apiCall(
    api,
    token,
    'get',
    `/timetable/class/${seed.classId}`,
    undefined,
    { view: 'latest', date: seed.targetDate },
  )
  const chinese = fridayEntries.find((row: any) => row.courseName === seed.courseChineseName)
  expect(chinese).toBeTruthy()
  expect(chinese.dayOfWeek).toBe(5)
  expect(chinese.periodNo).toBe(1)
  expect(chinese.substituted).toBe(true)
})
