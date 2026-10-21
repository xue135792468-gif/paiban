import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import AiDataImportView from './AiDataImportView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

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
      { params: { target: 'timetable' } },
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
