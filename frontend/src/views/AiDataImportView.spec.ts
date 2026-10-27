import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import AiDataImportView from './AiDataImportView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock, AI_IMPORT_TIMEOUT_MS: 300000 }))

/** The abandon flow asks before it throws the import away; the answer is the test's. */
const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return {
    ...actual,
    ElMessageBox: { ...(actual.ElMessageBox as Record<string, unknown>), confirm: confirmMock },
  }
})

function aiStatus(visionCapable = true) {
  return {
    data: { configured: true, tasks: { data_import: { configured: true, visionCapable } } },
  }
}

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/ai-import', component: { template: '<div />' } },
      { path: '/admin/teachers', component: { template: '<div />' } },
      { path: '/admin/drafts/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
}

async function mountImport(
  path = '/admin/ai-import',
  visionCapable = true,
  getOverride?: (url: string) => Promise<unknown> | null,
): Promise<{ wrapper: VueWrapper; router: Router }> {
  httpMock.get.mockImplementation((url: string) => {
    const override = getOverride?.(url)
    if (override) return override
    if (url === '/ai/status') return Promise.resolve(aiStatus(visionCapable))
    const session = url.match(/^\/ai\/import\/sessions\/(\d+)$/)
    if (session) {
      return Promise.resolve({
        data: { ...timetablePreview().data, sessionId: Number(session[1]) },
      })
    }
    return Promise.resolve({ data: [] })
  })
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AiDataImportView, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

function preview(targetType = 'teacher') {
  return {
    data: {
      sessionId: 7,
      targetType,
      columns: [{ field: 'name', label: '姓名', required: true }],
      rows: [{ values: { name: '张老师' }, confidence: { name: 0.9 }, lowConfidence: [] }],
      mappings: [],
      explanation: '',
      threshold: 0.7,
      status: 'PREVIEW',
    },
  }
}

function chooseFile(wrapper: VueWrapper, index: number, name: string) {
  const upload = wrapper.findAllComponents({ name: 'ElUpload' })[index]
  const onChange = upload.props('onChange') as (file: unknown, files: unknown[]) => void
  onChange({ raw: new File(['x'], name), name }, [])
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found
}

beforeEach(() => {
  localStorage.clear()
})

describe('AI 导入目标卡片', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  it('展示教师、课表两个可用目标和四个即将支持的目标', async () => {
    const { wrapper } = await mountImport()

    const cards = wrapper.findAll('[data-testid="target-card"]')
    expect(cards.map((card) => card.attributes('data-target'))).toEqual([
      'teacher',
      'timetable',
      'admin-class',
      'course',
      'room',
      'assignment',
    ])

    const teacher = wrapper.get('[data-testid="target-card"][data-target="teacher"]')
    const timetable = wrapper.get('[data-testid="target-card"][data-target="timetable"]')
    expect(teacher.classes()).not.toContain('is-disabled')
    expect(timetable.classes()).not.toContain('is-disabled')

    for (const code of ['admin-class', 'course', 'room', 'assignment']) {
      const card = wrapper.get(`[data-testid="target-card"][data-target="${code}"]`)
      expect(card.classes()).toContain('is-disabled')
      expect(card.attributes('aria-disabled')).toBe('true')
      expect(card.text()).toContain('即将支持')
    }

    await wrapper.get('[data-testid="target-card"][data-target="course"]').trigger('click')
    expect(wrapper.get('[data-testid="target-card"][data-target="teacher"]').text()).toContain(
      '已选择',
    )
  })

  it('每张卡片都写清能处理什么、输出什么和限制', async () => {
    const { wrapper } = await mountImport()

    for (const card of wrapper.findAll('[data-testid="target-card"]')) {
      const text = card.text()
      expect(text).toContain('能处理')
      expect(text).toContain('输出')
      expect(text).toContain('限制')
    }

    const teacher = wrapper.get('[data-testid="target-card"][data-target="teacher"]').text()
    expect(teacher).toContain('名单')
    expect(teacher).toContain('任课表')
    expect(teacher).toContain('教师档案')
    expect(teacher).toContain('图片')
    expect(teacher).toContain('视觉模型')

    const timetable = wrapper.get('[data-testid="target-card"][data-target="timetable"]').text()
    expect(timetable).toContain('班级课表')
    expect(timetable).toContain('班级 × 星期 × 节次')
    expect(timetable).toContain('草稿')
    expect(timetable).toContain('发布')
  })
})

describe('入口预选目标', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('target=timetable 时预选课表', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')

    expect(wrapper.get('[data-testid="target-card"][data-target="timetable"]').text()).toContain('已选择')
    expect(wrapper.get('[data-testid="target-card"][data-target="teacher"]').text()).not.toContain('已选择')
  })

  it('未知或尚未开放的目标回退到教师', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=course')

    expect(wrapper.get('[data-testid="target-card"][data-target="teacher"]').text()).toContain('已选择')
  })
})

describe('上传请求携带导入目标', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.post.mockResolvedValue(preview())
  })

  it('Excel 一律按整本工作簿上传，不受导入目标影响', async () => {
    const { wrapper } = await mountImport()

    chooseFile(wrapper, 0, 'teachers.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/excel', expect.any(FormData), {
      params: { target: 'workbook' },
      timeout: 300000,
    })
  })

  it('点选已选中的教师卡片也会切到单表流程', async () => {
    const { wrapper } = await mountImport()

    await wrapper.get('[data-testid="target-card"][data-target="teacher"]').trigger('click')
    chooseFile(wrapper, 0, 'teachers.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/excel', expect.any(FormData), {
      params: { target: 'teacher' },
      timeout: 300000,
    })
  })

  it('点选目标后上传 Excel 走该目标的单表流程', async () => {
    const { wrapper } = await mountImport()

    await wrapper.get('[data-testid="target-card"][data-target="timetable"]').trigger('click')
    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/excel', expect.any(FormData), {
      params: { target: 'timetable' },
      timeout: 300000,
    })
  })

  it('从入口预选课表后粘贴文本也带上 target=timetable', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')

    const textTab = wrapper.findAll('.el-tabs__item').find((tab) => tab.text() === '粘贴文本')
    if (!textTab) throw new Error('text tab not found')
    await textTab.trigger('click')
    await wrapper.get('textarea').setValue('高一（1）班 周一第 1 节 语文 张老师')
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/ai/import/text',
      { text: '高一（1）班 周一第 1 节 语文 张老师' },
      { params: { target: 'timetable' }, timeout: 300000 },
    )
  })
})

describe('图片入口受视觉模型能力控制', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('当前模型不支持视觉时禁用图片入口并说明原因', async () => {
    const { wrapper } = await mountImport('/admin/ai-import', false)

    expect(wrapper.get('[data-testid="vision-warning"]').text()).toContain(
      '当前模型不支持图片，请在设置中换用视觉模型',
    )
    const imageTab = wrapper.get('[data-testid="image-tab"]')
    expect(imageTab.element.closest('.el-tabs__item')?.classList.contains('is-disabled')).toBe(true)
  })

  it('模型支持视觉时图片入口可用', async () => {
    const { wrapper } = await mountImport('/admin/ai-import', true)

    expect(wrapper.find('[data-testid="vision-warning"]').exists()).toBe(false)
    const imageTab = wrapper.get('[data-testid="image-tab"]')
    expect(imageTab.element.closest('.el-tabs__item')?.classList.contains('is-disabled')).toBe(false)
  })

  it('课表目标下图片入口不可用（图片导入暂只支持教师）', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable', true)

    const imageTab = wrapper.get('[data-testid="image-tab"]')
    expect(imageTab.element.closest('.el-tabs__item')?.classList.contains('is-disabled')).toBe(true)
  })
})

describe('预览反映导入目标', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('预览头部显示后端返回的 targetType', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockResolvedValue(preview('timetable'))

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="preview-target"]').text()).toContain('课表')
  })

  it('教师预览显示教师目标', async () => {
    const { wrapper } = await mountImport()
    httpMock.post.mockResolvedValue(preview('teacher'))

    chooseFile(wrapper, 0, 'teachers.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="preview-target"]').text()).toContain('教师')
  })
})

describe('AI 目标提议', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('AI 提议与当前目标不同时显示可操作提示，但不自动切换', async () => {
    const { wrapper } = await mountImport()
    await showTimetable(
      wrapper,
      timetablePreview({ targetType: 'teacher', suggestedTarget: 'timetable' }),
    )

    expect(wrapper.get('[data-testid="preview-target"]').text()).toContain('教师')
    const hint = wrapper.get('[data-testid="suggested-target-hint"]')
    expect(hint.text()).toContain('AI 认为这更像课表')
    expect(hint.text()).toContain('切换目标')
  })

  it('点击切换后回到目标选择并预选 AI 提议的目标', async () => {
    const { wrapper } = await mountImport()
    await showTimetable(
      wrapper,
      timetablePreview({ targetType: 'teacher', suggestedTarget: 'timetable' }),
    )

    await button(wrapper, '切换目标').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="suggested-target-hint"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="target-card"][data-target="timetable"]').text(),
    ).toContain('已选择')
  })

  it('AI 提议与当前目标一致时不显示提示', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, timetablePreview({ suggestedTarget: 'timetable' }))

    expect(wrapper.find('[data-testid="suggested-target-hint"]').exists()).toBe(false)
  })
})

function timetableRow(
  className: string,
  day: string,
  period: string,
  course: string,
  teacher: string,
) {
  return {
    values: {
      className,
      dayOfWeek: day,
      periodNo: period,
      courseName: course,
      teacherName: teacher,
      roomName: '101',
    },
    confidence: {},
    lowConfidence: [],
  }
}

function timetablePreview(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      sessionId: 9,
      targetType: 'timetable',
      columns: [
        { field: 'className', label: '班级', required: true },
        { field: 'dayOfWeek', label: '星期', required: true },
        { field: 'periodNo', label: '节次', required: true },
        { field: 'courseName', label: '课程', required: true },
        { field: 'teacherName', label: '教师', required: true },
        { field: 'roomName', label: '教室', required: false },
      ],
      rows: [
        timetableRow('一年级1班', '周一', '1', '语文', '张三'),
        timetableRow('一年级1班', '周一', '2', '数学', '李四'),
      ],
      mappings: [],
      explanation: '',
      threshold: 0.7,
      status: 'PREVIEW',
      issues: [],
      summary: { lessons: 2, unresolved: 0, groups: 2, newAssignments: 2, updatedAssignments: 0 },
      ...overrides,
    },
  }
}

function unresolvedIssue() {
  return {
    row: 1,
    field: 'className',
    value: '一年级(1)班',
    candidates: ['一年级1班'],
    required: true,
  }
}

async function showTimetable(wrapper: VueWrapper, response: unknown = timetablePreview()) {
  httpMock.post.mockResolvedValue(response)
  chooseFile(wrapper, 0, 'grade-1.xlsx')
  await flushPromises()
  await button(wrapper, '开始解析').trigger('click')
  await flushPromises()
}

function commitButton(wrapper: VueWrapper) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().includes('确认入库'))
  if (!found) throw new Error('commit button not found')
  return found
}

function mappingSelect(wrapper: VueWrapper) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === 'resolve-className')
  if (!found) throw new Error('mapping select not found')
  return found
}

describe('课表预览网格与摘要', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('课表目标使用只读网格，并显示将创建与更新的授课任务数', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper)

    const summary = wrapper.get('[data-testid="timetable-summary"]').text()
    expect(summary).toContain('共 2 节，0 节待映射')
    expect(summary).toContain('将创建 2 条授课任务')
    expect(summary).toContain('将更新 0 条授课任务')
    expect(wrapper.find('.grid').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preview-rows-table"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="mapping-clear"]').text()).toContain('可以确认入库')
  })

  it('教师目标仍然使用行表格', async () => {
    const { wrapper } = await mountImport()
    await showTimetable(wrapper, preview('teacher'))

    expect(wrapper.find('[data-testid="preview-rows-table"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="timetable-summary"]').exists()).toBe(false)
  })

  it('课表预览可以切换按班级与按教师查看', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper)

    const mode = wrapper.get('[data-testid="timetable-view-mode"]')
    expect(mode.text()).toContain('按班级')
    expect(mode.text()).toContain('按教师')
    expect(wrapper.get('[data-testid="timetable-view-select"]').text()).toContain('一年级1班')
  })
})

describe('映射步骤与入库可用性', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('存在未解决映射时禁用确认入库并显示剩余数量', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, timetablePreview({ issues: [unresolvedIssue()] }))

    const commit = commitButton(wrapper)
    expect(commit.text()).toContain('还有 1 项未解决')
    expect((commit.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('[data-testid="mapping-step"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="mapping-step"]').text()).toContain('一年级(1)班')
  })

  it('映射完成后刷新预览并解除入库禁用', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, timetablePreview({ issues: [unresolvedIssue()] }))

    httpMock.get.mockImplementation((url: string) => {
      if (url === '/ai/status') return Promise.resolve(aiStatus())
      if (url === '/ai/import/lookup') {
        return Promise.resolve({ data: [{ id: 3, name: '一年级1班', extra: '一年级' }] })
      }
      return Promise.resolve({ data: [] })
    })
    httpMock.post.mockResolvedValue({ data: timetablePreview().data })

    mappingSelect(wrapper).vm.$emit('change', '一年级1班')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [{ field: 'className', source: '一年级(1)班', targetId: 3 }],
      creates: [],
    })
    const commit = commitButton(wrapper)
    expect(commit.text()).toBe('确认入库')
    expect((commit.element as HTMLButtonElement).disabled).toBe(false)
    expect(wrapper.find('[data-testid="mapping-clear"]').exists()).toBe(true)
  })

  it('入库成功后跳转到生成的草稿', async () => {
    const { wrapper, router } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper)
    httpMock.post.mockResolvedValue({
      data: { imported: 2, errors: [], warnings: ['班级 一年级1班 有多位教师'], draftId: 42 },
    })

    await commitButton(wrapper).trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/commit')
    expect(router.currentRoute.value.path).toBe('/admin/drafts/42')
  })

  it('解析期间显示大表可能需要 1–2 分钟的提示', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    let finish: (value: unknown) => void = () => {}
    httpMock.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="parse-progress"]').text()).toContain(
      'AI 正在解析，大表会按班分批，可能需要几分钟',
    )

    finish(timetablePreview())
    await flushPromises()
  })
})

describe('没有当前学期时的阻塞提示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('显示阻塞原因并禁用入库，且不渲染映射步骤', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(
      wrapper,
      timetablePreview({
        issues: [
          {
            row: 0,
            field: 'semester',
            value: '',
            candidates: [],
            required: false,
            message: '请先设置当前学期',
          },
        ],
        summary: { lessons: 2, unresolved: 2, groups: 0, newAssignments: 0, updatedAssignments: 0 },
      }),
    )

    expect(wrapper.get('[data-testid="blocking-issue"]').text()).toContain('请先设置当前学期')
    const commit = commitButton(wrapper)
    expect((commit.element as HTMLButtonElement).disabled).toBe(true)
    expect(commit.text()).toContain('还有 1 项未解决')
    expect(wrapper.find('[data-testid="mapping-step"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mapping-clear"]').exists()).toBe(false)
  })
})

describe('会话恢复', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('挂载时按 ?session 恢复预览，并把会话留在 URL 里', async () => {
    const { wrapper, router } = await mountImport('/admin/ai-import?target=timetable&session=9')

    expect(httpMock.get).toHaveBeenCalledWith('/ai/import/sessions/9')
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="preview-target"]').text()).toContain('课表')
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    expect(router.currentRoute.value.query.session).toBe('9')
  })

  it('恢复已微调会话时显示已微调并保留入库操作', async () => {
    seedLastSession({ status: 'EDITED' })
    const { wrapper } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9'
        ? Promise.resolve({ data: { ...timetablePreview().data, status: 'EDITED' } })
        : null,
    )

    expect(wrapper.get('.head-actions').text()).toContain('已微调')
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    const commit = commitButton(wrapper)
    expect(commit.text()).toBe('确认入库')
    expect((commit.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('恢复遇到临时失败时保留 URL 会话，刷新可以重试', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9' ? Promise.reject({ response: { status: 500 } }) : null,
    )

    expect(error).toHaveBeenCalled()
    expect(router.currentRoute.value.query.session).toBe('9')
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preview-target"]').exists()).toBe(false)
    expect(button(wrapper, '开始解析').exists()).toBe(true)
    error.mockRestore()
  })

  it('恢复失败时给出可读错误、回到上传表单，并清掉 URL 与会话记录', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    seedLastSession()
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9'
        ? Promise.reject({ response: { status: 404 } })
        : null,
    )

    expect(error).toHaveBeenCalledWith(expect.stringContaining('已不存在'))
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(button(wrapper, '开始解析').exists()).toBe(true)
    expect(wrapper.find('[data-testid="last-session-card"]').exists()).toBe(false)
    expect(router.currentRoute.value.query.session).toBeUndefined()
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()
    error.mockRestore()
  })

  it('解析成功后把 session 与 target 写进 URL，并记住上次导入', async () => {
    const { wrapper, router } = await mountImport('/admin/ai-import')
    httpMock.post.mockResolvedValue(timetablePreview())

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.session).toBe('9')
    expect(router.currentRoute.value.query.target).toBe('teacher')
    const stored = JSON.parse(localStorage.getItem('paiban.aiImport.lastSession') ?? '{}')
    expect(stored).toMatchObject({ id: 9, fileName: 'grade-1.xlsx', status: 'PREVIEW' })
    expect(typeof stored.savedAt).toBe('string')
  })

  it('粘贴文本导入时，上次导入显示为粘贴文本', async () => {
    const { wrapper } = await mountImport()
    httpMock.post.mockResolvedValue(preview('teacher'))

    const textTab = wrapper.findAll('.el-tabs__item').find((tab) => tab.text() === '粘贴文本')
    if (!textTab) throw new Error('text tab not found')
    await textTab.trigger('click')
    await wrapper.get('textarea').setValue('张老师 语文 13800000001')
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(readStoredStatus()).toBe('PREVIEW')
    const stored = JSON.parse(localStorage.getItem('paiban.aiImport.lastSession') ?? '{}')
    expect(stored.fileName).toBe('粘贴文本')
  })

  it('空页面显示上次导入卡片，点继续恢复该会话', async () => {
    seedLastSession({ savedAt: '2026-10-23T10:00:00' })
    const { wrapper, router } = await mountImport()

    const card = wrapper.get('[data-testid="last-session-card"]')
    expect(card.text()).toContain('grade-1.xlsx')
    expect(card.text()).toContain('待确认')
    expect(card.text()).toContain('2026-10-23 10:00')

    await wrapper.get('[data-testid="continue-last-session"]').trigger('click')
    await flushPromises()

    expect(httpMock.get).toHaveBeenCalledWith('/ai/import/sessions/9')
    expect(wrapper.find('[data-testid="last-session-card"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="preview-target"]').text()).toContain('课表')
    expect(router.currentRoute.value.query.session).toBe('9')
  })

  it('恢复已入库会话时显示已入库，不编造入库条数', async () => {
    seedLastSession()
    const { wrapper } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9'
        ? Promise.resolve({ data: { ...timetablePreview().data, status: 'COMMITTED' } })
        : null,
    )

    expect(wrapper.get('.head-actions').text()).toContain('已入库')
    const alert = wrapper.get('.result-alert')
    expect(alert.text()).toContain('该导入已入库')
    expect(alert.text()).not.toContain('0 条')
    const stored = JSON.parse(localStorage.getItem('paiban.aiImport.lastSession') ?? '{}')
    expect(stored.status).toBe('COMMITTED')
  })

  it('恢复失败会话时显示失败原因并收起入库操作', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9'
        ? Promise.resolve({
            data: {
              ...timetablePreview().data,
              status: 'FAILED',
              explanation: '模型返回的表格为空，请检查文件内容',
            },
          })
        : null,
    )

    expect(wrapper.get('[data-testid="failed-reason"]').text()).toContain(
      '模型返回的表格为空',
    )
    expect(wrapper.get('.head-actions').text()).toContain('解析失败')
    expect(wrapper.find('.footer-actions').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mapping-step"]').exists()).toBe(false)
  })

  it('未知状态回退到空的新导入页，并移除 URL 会话', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    seedLastSession({ status: 'QUEUED' })
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9'
        ? Promise.resolve({ data: { ...timetablePreview().data, status: 'QUEUED' } })
        : null,
    )

    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preview-target"]').exists()).toBe(false)
    expect(router.currentRoute.value.query.session).toBeUndefined()
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()
    expect(error).toHaveBeenCalled()
    error.mockRestore()
  })

  it('重新导入会移除 URL 里的 session', async () => {
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9')

    await button(wrapper, '重新导入').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(router.currentRoute.value.query.session).toBeUndefined()
  })

  it('入库后把上次导入的状态更新为已入库', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper)
    expect(readStoredStatus()).toBe('PREVIEW')

    httpMock.post.mockResolvedValue({
      data: { imported: 2, errors: [], warnings: [], draftId: 42 },
    })
    await commitButton(wrapper).trigger('click')
    await flushPromises()

    expect(readStoredStatus()).toBe('COMMITTED')
  })
})

describe('停止解析', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function stoppedPreview() {
    return timetablePreview({
      status: 'PREVIEW',
      progress: { total: 4, completed: 1, failed: 0, currentBatch: null },
    })
  }

  it('解析中提供停止按钮，停止后提示保留的批次并进入预览', async () => {
    vi.useFakeTimers()
    const success = vi.spyOn(ElMessage, 'success')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/ai/import/excel') return Promise.resolve(parsingPreview())
      if (url === '/ai/import/sessions/9/stop') return Promise.resolve(stoppedPreview())
      return Promise.resolve({ data: {} })
    })

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="import-progress"]').text()).toContain('停止解析')
    await wrapper.get('[data-testid="stop-parsing"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/stop')
    expect(success).toHaveBeenCalledWith('已停止解析，保留已完成 1/4 批')
    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    expect(wrapper.get('.head-actions').text()).toContain('待确认')

    const settled = sessionCalls()
    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()
    expect(sessionCalls()).toBe(settled)
    success.mockRestore()
  })

  it('第一批都没解析完就停止时说明没有任何已完成批次，不给出可入库的预览', async () => {
    vi.useFakeTimers()
    const warning = vi.spyOn(ElMessage, 'warning')
    const success = vi.spyOn(ElMessage, 'success')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/ai/import/excel') return Promise.resolve(parsingPreview())
      if (url === '/ai/import/sessions/9/stop') {
        return Promise.resolve({
          data: {
            ...timetablePreview({ progress: { total: 4, completed: 0, failed: 0, currentBatch: null } }).data,
            status: 'FAILED',
            rows: [],
            explanation: '已停止解析，没有任何已完成的批次',
          },
        })
      }
      return Promise.resolve({ data: {} })
    })

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="stop-parsing"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('已停止解析，没有任何已完成的批次')
    expect(success).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="failed-reason"]').text()).toContain(
      '已停止解析，没有任何已完成的批次',
    )
    expect(wrapper.find('.footer-actions').exists()).toBe(false)
    warning.mockRestore()
    success.mockRestore()
  })

  it('工作簿有计划但 0 批完成时停止，提示未完成范围而不是「保留 0/N」', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const success = vi.spyOn(ElMessage, 'success')
    const { wrapper } = await mountImport('/admin/ai-import')
    const parsing = {
      data: {
        ...timetablePreview().data,
        targetType: 'workbook',
        status: 'PARSING',
        rows: [],
        progress: {
          total: 3,
          completed: 0,
          failed: 0,
          currentBatch: '工作表「数据源」第 1-2 行',
          phase: 'EXTRACTING',
        },
        workbook: { structureConfirmed: true, sheets: [], committed: [] },
      },
    }
    const stopped = {
      data: {
        ...parsing.data,
        status: 'PARTIAL',
        explanation: '已停止解析，没有任何已完成的批次',
        progress: { total: 3, completed: 0, failed: 3, phase: 'EXTRACTING' },
        failedBatches: [
          { index: 1, label: '工作表「数据源」第 1-2 行', reason: '已停止解析，这一批的结果未采用' },
        ],
      },
    }
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/ai/import/excel') return Promise.resolve(parsing)
      if (url.endsWith('/stop')) return Promise.resolve(stopped)
      return Promise.resolve({ data: {} })
    })

    chooseFile(wrapper, 0, 'workbook.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="stop-parsing"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('已停止解析，没有任何已完成的批次')
    expect(success).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="partial-result-panel"]').exists()).toBe(true)
    warning.mockRestore()
    success.mockRestore()
  })

  it('停止被拒绝时展示后端原因并继续轮询', async () => {
    vi.useFakeTimers()
    const error = vi.spyOn(ElMessage, 'error')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/ai/import/excel') return Promise.resolve(parsingPreview())
      if (url === '/ai/import/sessions/9/stop') {
        return Promise.reject({ response: { status: 409, data: { message: '当前导入不在解析中，无法停止' } } })
      }
      return Promise.resolve({ data: {} })
    })

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="stop-parsing"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith('当前导入不在解析中，无法停止')
    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(true)
    error.mockRestore()
  })
})

describe('整本工作簿后台化', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('规划阶段显示识别结构，不显示伪造的 0/0 批次', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=workbook')
    httpMock.post.mockResolvedValue({
      data: {
        ...timetablePreview().data,
        targetType: 'workbook',
        status: 'PARSING',
        rows: [],
        progress: { total: 0, completed: 0, failed: 0, currentBatch: null, phase: 'PLANNING' },
        workbook: null,
      },
    })

    chooseFile(wrapper, 0, 'book.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    const progress = wrapper.get('[data-testid="import-progress"]')
    expect(progress.text()).toContain('正在识别工作表结构')
    expect(progress.text()).not.toContain('已完成 0/0 批')
  })

  it('规划失败后提供明确的重新规划入口', async () => {
    confirmMock.mockResolvedValue('confirm')
    const failed = {
      data: {
        ...timetablePreview().data,
        targetType: 'workbook',
        status: 'FAILED',
        rows: [],
        explanation: 'AI 解析失败：stub 规划失败',
        progress: null,
        workbook: null,
        issues: [],
      },
    }
    const { wrapper } = await mountImport(
      '/admin/ai-import?session=9',
      true,
      (url) => (url === '/ai/import/sessions/9' ? Promise.resolve(failed) : null),
    )

    expect(wrapper.get('[data-testid="failed-reason"]').text()).toContain('AI 解析失败')
    httpMock.post.mockResolvedValue({
      data: {
        ...failed.data,
        status: 'PARSING',
        explanation: '正在识别工作簿结构',
        progress: { total: 0, completed: 0, failed: 0, currentBatch: null, phase: 'PLANNING' },
      },
    })

    await button(wrapper, '重新规划').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalled()
    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/reparse', undefined, {
      timeout: 300000,
    })
    expect(wrapper.get('[data-testid="import-progress"]').text()).toContain(
      '正在识别工作表结构',
    )
  })
})

function readStoredStatus(): string | undefined {
  return JSON.parse(localStorage.getItem('paiban.aiImport.lastSession') ?? '{}').status
}

function partialPreview(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      ...timetablePreview().data,
      status: 'PARTIAL',
      progress: { total: 4, completed: 2, failed: 2, currentBatch: null },
      failedBatches: [
        { index: 2, label: '一年级2班', reason: '第 2/4 批（一年级2班）解析失败：模型返回为空' },
        { index: 4, label: '一年级4班', reason: '第 4/4 批（一年级4班）解析失败：超时' },
      ],
      ...overrides,
    },
  }
}

describe('部分结果（PARTIAL）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('用部分结果面板替换预览网格，禁用入库并提示先重试或放弃', async () => {
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())

    const panel = wrapper.get('[data-testid="partial-result-panel"]')
    expect(panel.text()).toContain('一年级2班')
    expect(panel.text()).toContain('一年级4班')
    expect(panel.text()).toContain('模型返回为空')
    expect(wrapper.get('[data-testid="partial-hint"]').text()).toContain(
      '请先重试失败批次或放弃导入',
    )
    expect(wrapper.get('.head-actions').text()).toContain('部分结果')
    expect(wrapper.find('[data-testid="timetable-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mapping-step"]').exists()).toBe(false)

    const commit = button(wrapper, '先重试或放弃')
    expect((commit.element as HTMLButtonElement).disabled).toBe(true)
    const reparse = button(wrapper, '打回重析')
    expect((reparse.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('用 ?session 恢复 PARTIAL 会话时正常渲染，不当作未知状态', async () => {
    seedLastSession({ status: 'PARTIAL' })
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9' ? Promise.resolve(partialPreview()) : null,
    )

    expect(wrapper.get('.head-actions').text()).toContain('部分结果')
    expect(wrapper.find('[data-testid="partial-result-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(false)
    expect(router.currentRoute.value.query.session).toBe('9')
  })
})

describe('重试失败批次', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** The retry answers PARSING with this round in flight: 3 batches done, 1 to go. */
  function retryInFlight() {
    return parsingPreview({
      rows: [],
      progress: { total: 4, completed: 3, failed: 1, currentBatch: '一年级2班' },
      failedBatches: [
        { index: 2, label: '一年级2班', reason: '第 2/4 批（一年级2班）解析失败：模型返回为空' },
      ],
    })
  }

  it('点击重试后显示本轮进度，轮询到预览后提示重试成功', async () => {
    vi.useFakeTimers()
    const success = vi.spyOn(ElMessage, 'success')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())

    httpMock.post.mockResolvedValue(retryInFlight())
    await wrapper.get('[data-testid="partial-retry"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/retry')
    const progress = wrapper.get('[data-testid="import-progress"]')
    expect(progress.text()).toContain('已完成 3/4 批')
    expect(progress.text()).toContain('一年级2班')

    httpMock.get.mockImplementation((url: string) => {
      if (url === '/ai/status') return Promise.resolve(aiStatus())
      if (url === '/ai/import/sessions/9') return Promise.resolve(timetablePreview())
      return Promise.resolve({ data: [] })
    })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    expect(success).toHaveBeenCalledWith('重试成功，已解析全部 2 节课')
    success.mockRestore()
  })

  it('重试仍然失败时回到部分结果面板，并提示还剩几个班', async () => {
    vi.useFakeTimers()
    const warning = vi.spyOn(ElMessage, 'warning')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())

    httpMock.post.mockResolvedValue(retryInFlight())
    await wrapper.get('[data-testid="partial-retry"]').trigger('click')
    await flushPromises()

    const stillFailing = partialPreview({
      progress: { total: 4, completed: 3, failed: 1, currentBatch: null },
      failedBatches: [
        {
          index: 2,
          label: '一年级2班',
          reason: '第 2/4 批（一年级2班）解析失败：重试时服务超时',
        },
      ],
    })
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/ai/status') return Promise.resolve(aiStatus())
      if (url === '/ai/import/sessions/9') return Promise.resolve(stillFailing)
      return Promise.resolve({ data: [] })
    })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    const panel = wrapper.get('[data-testid="partial-result-panel"]')
    expect(panel.text()).toContain('重试时服务超时')
    expect(panel.text()).not.toContain('模型返回为空')
    expect(warning).toHaveBeenCalledWith('重试后还有 1 个批次没解析出来，可以再试一次或放弃导入')
    warning.mockRestore()
  })

  it('重试被拒绝时展示后端原因并留在部分结果', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())

    httpMock.post.mockRejectedValue({
      response: { status: 409, data: { message: '当前导入没有可重试失败批次' } },
    })
    await wrapper.get('[data-testid="partial-retry"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith('当前导入没有可重试失败批次')
    expect(wrapper.find('[data-testid="partial-result-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    error.mockRestore()
  })
})

function seedLastSession(overrides: Record<string, unknown> = {}) {
  const record = {
    id: 9,
    fileName: 'grade-1.xlsx',
    status: 'PREVIEW',
    savedAt: '2026-10-23T10:00:00',
    ...overrides,
  }
  localStorage.setItem('paiban.aiImport.lastSession', JSON.stringify(record))
  return record
}

function parsingPreview(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      ...timetablePreview().data,
      status: 'PARSING',
      rows: [],
      issues: [],
      summary: null,
      progress: { total: 4, completed: 1, failed: 0, currentBatch: '一年级2班' },
      failedBatches: [],
      ...overrides,
    },
  }
}

function sessionCalls(): number {
  return httpMock.get.mock.calls.filter(([url]) => url === '/ai/import/sessions/9').length
}

function abandonedPreview(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      ...timetablePreview().data,
      status: 'ABANDONED',
      progress: { total: 4, completed: 1, failed: 0, currentBatch: null },
      failedBatches: [],
      ...overrides,
    },
  }
}

describe('放弃导入', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue('confirm')
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('解析中提供放弃入口，确认后清空预览、URL 与上次导入记录', async () => {
    vi.useFakeTimers()
    const info = vi.spyOn(ElMessage, 'info')
    const { wrapper, router } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/ai/import/excel') return Promise.resolve(parsingPreview())
      if (url === '/ai/import/sessions/9/abandon') return Promise.resolve(abandonedPreview())
      return Promise.resolve({ data: {} })
    })

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="abandon-import"]').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledWith(
      expect.stringContaining('不会写入任何数据'),
      '放弃导入',
      expect.anything(),
    )
    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/abandon')
    expect(info).toHaveBeenCalledWith(expect.stringContaining('已放弃'))
    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(router.currentRoute.value.query.session).toBeUndefined()
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()

    // The poll timer is gone with the session: nothing keeps calling the server.
    const settled = sessionCalls()
    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()
    expect(sessionCalls()).toBe(settled)
    info.mockRestore()
  })

  it('部分结果提供放弃入口，确认后回到上传表单', async () => {
    const info = vi.spyOn(ElMessage, 'info')
    const { wrapper, router } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())
    httpMock.post.mockResolvedValue(abandonedPreview())

    expect(wrapper.get('[data-testid="partial-result-panel"]').text()).toContain('放弃导入')
    await wrapper.get('[data-testid="abandon-import"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/abandon')
    expect(info).toHaveBeenCalledWith(expect.stringContaining('已放弃'))
    expect(wrapper.find('[data-testid="partial-result-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(router.currentRoute.value.query.session).toBeUndefined()
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()
    info.mockRestore()
  })

  it('确认框被取消时什么都不做', async () => {
    confirmMock.mockRejectedValue('cancel')
    const { wrapper, router } = await mountImport('/admin/ai-import?target=timetable')
    await showTimetable(wrapper, partialPreview())

    await wrapper.get('[data-testid="abandon-import"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).not.toHaveBeenCalledWith('/ai/import/sessions/9/abandon')
    expect(wrapper.find('[data-testid="partial-result-panel"]').exists()).toBe(true)
    expect(router.currentRoute.value.query.session).toBe('9')
  })

  it('恢复入口不再把已放弃会话当作可继续的导入', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    seedLastSession({ status: 'ABANDONED' })
    const { wrapper, router } = await mountImport('/admin/ai-import?session=9', true, (url) =>
      url === '/ai/import/sessions/9' ? Promise.resolve(abandonedPreview()) : null,
    )

    expect(wrapper.find('[data-testid="target-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preview-target"]').exists()).toBe(false)
    expect(router.currentRoute.value.query.session).toBeUndefined()
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()
    expect(error).toHaveBeenCalled()
    error.mockRestore()
  })

  it('空页面不展示已放弃的上次导入', async () => {
    seedLastSession({ status: 'ABANDONED' })
    const { wrapper } = await mountImport()

    expect(wrapper.find('[data-testid="last-session-card"]').exists()).toBe(false)
    expect(localStorage.getItem('paiban.aiImport.lastSession')).toBeNull()
  })
})

describe('大表后台解析与进度', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('上传后先显示解析进度，轮询到完成后自动进入预览', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockResolvedValue(parsingPreview())

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    const panel = wrapper.get('[data-testid="import-progress"]')
    expect(panel.text()).toContain('已完成 1/4 批')
    expect(panel.text()).toContain('一年级2班')
    expect(wrapper.find('[data-testid="timetable-summary"]').exists()).toBe(false)
    expect(
      wrapper.findAll('button').some((candidate) => candidate.text().includes('确认入库')),
    ).toBe(false)

    httpMock.get.mockImplementation((url: string) => {
      if (url === '/ai/status') return Promise.resolve(aiStatus())
      if (url === '/ai/import/sessions/9') return Promise.resolve(timetablePreview())
      return Promise.resolve({ data: [] })
    })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    expect(wrapper.get('.head-actions').text()).toContain('待确认')
  })

  it('解析完成后停止轮询', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountImport('/admin/ai-import?target=timetable')
    httpMock.post.mockResolvedValue(parsingPreview())

    chooseFile(wrapper, 0, 'grade-1.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    httpMock.get.mockImplementation((url: string) => {
      if (url === '/ai/status') return Promise.resolve(aiStatus())
      if (url === '/ai/import/sessions/9') return Promise.resolve(timetablePreview())
      return Promise.resolve({ data: [] })
    })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    const settled = sessionCalls()

    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()

    expect(sessionCalls()).toBe(settled)
  })

  it('用 ?session 恢复时若仍在解析则继续轮询到预览', async () => {
    vi.useFakeTimers()
    let polls = 0
    const { wrapper } = await mountImport('/admin/ai-import?session=9', true, (url) => {
      if (url !== '/ai/import/sessions/9') return null
      polls += 1
      return Promise.resolve(polls === 1 ? parsingPreview() : timetablePreview())
    })

    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="import-progress"]').text()).toContain('解析中')

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    expect(wrapper.find('[data-testid="import-progress"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="timetable-summary"]').text()).toContain('共 2 节')
    expect(polls).toBe(2)
  })
})
