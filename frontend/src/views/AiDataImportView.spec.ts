import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import AiDataImportView from './AiDataImportView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock, AI_IMPORT_TIMEOUT_MS: 300000 }))

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
): Promise<{ wrapper: VueWrapper; router: Router }> {
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/ai/status') return Promise.resolve(aiStatus(visionCapable))
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

  it('默认以教师为目标上传 Excel', async () => {
    const { wrapper } = await mountImport()

    chooseFile(wrapper, 0, 'teachers.xlsx')
    await flushPromises()
    await button(wrapper, '开始解析').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/excel', expect.any(FormData), {
      params: { target: 'teacher' },
      timeout: 300000,
    })
  })

  it('选中课表后上传 Excel 带上 target=timetable', async () => {
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
    .find((component) => component.element.closest('[data-testid="mapping-step"]'))
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
