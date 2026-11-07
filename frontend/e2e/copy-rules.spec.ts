import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  pickOption,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'

/** One entry-backed teacher row; every copied card carries its own group key. */
function teacherEntry(
  teacherId: number,
  dayOfWeek: number,
  periodNo: number,
  groupKey: string,
) {
  return {
    params: { teacherId, dayOfWeek, periodNo },
    enabled: true,
    hard: true,
    weight: null,
    groupKey,
  }
}

function teacherRule(entries: ReturnType<typeof teacherEntry>[]) {
  return {
    type: 'TEACHER_UNAVAILABLE',
    params: {},
    weight: 100,
    hard: true,
    enabled: true,
    entries,
  }
}

/**
 * 票 08：从往期学期整套复制规则。源学期保存两条教师规则，其中一条引用的教师
 * 随后被删除；目标学期先有一条旧规则。复制后旧规则消失、正常规则照常启用、
 * 失效规则进入「本学期用不上」。
 */
test.describe('排课规则：从以前的学期复制', () => {
  let api: APIRequestContext
  let token: string
  let source: ConstraintSeed
  let target: ConstraintSeed
  let sourceName: string

  async function saveConfigs(semesterId: number, configs: unknown[]) {
    const state = await apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId,
    })
    await apiCall(api, token, 'put', '/constraints', { configs }, {
      semesterId,
      baseVersion: state.version,
    })
  }

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    // The second seed becomes the current semester; the first is the past one.
    source = await seedConstraintScenario(api)
    target = await seedConstraintScenario(api)
    sourceName = (await apiCall(api, token, 'get', `/semesters/${source.semesterId}`)).name

    const [removedTeacher, keptTeacher] = source.teachers
    await saveConfigs(source.semesterId, [
      teacherRule([
        teacherEntry(keptTeacher.id, 1, 1, 'copy-source-ok'),
        teacherEntry(removedTeacher.id, 2, 2, 'copy-source-gone'),
      ]),
    ])
    // The source card now points at a teacher that no longer exists.
    await apiCall(api, token, 'delete', `/teachers/${removedTeacher.id}`)

    // The target's own rule must disappear after the replacement.
    await saveConfigs(target.semesterId, [
      teacherRule([teacherEntry(target.teachers[0].id, 3, 2, 'target-old')]),
    ])
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  async function state() {
    return apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: target.semesterId,
    })
  }

  test('确认替换后显示结果，并把失效规则列进「本学期用不上」', async ({ page }) => {
    const [removedTeacher, keptTeacher] = source.teachers

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    await page.getByTestId('rule-copy-open').click()
    const dialog = page.getByTestId('rule-copy-dialog')
    await expect(dialog).toBeVisible()
    await pickOption(dialog, 'rule-copy-source', sourceName)
    await expect(page.getByTestId('rule-copy-confirm')).toBeEnabled()

    const copied = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/constraints/copy-rules') &&
        response.request().method() === 'POST',
    )
    await page.getByTestId('rule-copy-confirm').click()
    expect((await copied).status()).toBe(200)

    await expect(page.getByTestId('rule-copy-result')).toContainText(
      '已复制 2 张规则卡，其中 1 张本学期用不上。',
    )
    await expect(page.getByTestId('rule-copy-issue')).toContainText(
      `教师已删除：#${removedTeacher.id}`,
    )

    await page.getByTestId('rule-copy-close').click()
    await expect(dialog).toBeHidden()

    // The normal card stays in the grouped list; the broken one is listed apart.
    await expect(page.getByTestId('rule-unusable-panel')).toContainText('本学期用不上（1）')
    await expect(page.getByTestId('rule-unusable-panel')).toContainText(/教师已(删除|停用)/)
    await expect(page.getByTestId('rule-cards')).toContainText(keptTeacher.name)
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-system-baseline')).toContainText('系统自带，不能关')

    // Server side: two copied rows, one paused; the target's old rule is gone.
    const after = await state()
    const teacherConfig = after.configs.find(
      (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
    )
    expect(teacherConfig.entries).toHaveLength(2)
    expect(teacherConfig.entries.map((entry: { enabled: boolean }) => entry.enabled).sort())
      .toEqual([false, true])
    expect(
      teacherConfig.entries.some(
        (entry: { params: Record<string, unknown> }) =>
          entry.params.teacherId === target.teachers[0].id,
      ),
    ).toBe(false)

    // Reload proves the paused card and its reason come from stored state.
    await page.reload()
    await expect(page.getByTestId('rule-unusable-panel')).toContainText('本学期用不上（1）')
    await expect(page.getByTestId('rule-unusable-panel')).toContainText(/教师已(删除|停用)/)
    await expect(page.getByTestId('rule-cards')).toContainText(keptTeacher.name)
  })
})
