import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import ConstraintTemplateBar from './ConstraintTemplateBar.vue'
import type { TemplateApplyPreview, TemplateItem, TemplateReferenceIssue } from './types'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))
vi.mock('@/api/http', () => ({ http: httpMock }))

const TEMPLATES: TemplateItem[] = [{ id: 4, name: '标准学期', configCount: 5, entryCount: 9 }]

function issue(overrides: Partial<TemplateReferenceIssue> = {}): TemplateReferenceIssue {
  return {
    type: 'CLASS_MAX_PER_DAY',
    typeName: '班级一天最多 N 节',
    position: 2,
    field: 'classId',
    fieldLabel: '班级',
    objectId: 31,
    name: '高一1班',
    reason: '行政班属于其他学期（2025 秋），请显式指定替代班级',
    ...overrides,
  }
}

function preview(overrides: Partial<TemplateApplyPreview> = {}): TemplateApplyPreview {
  return { configCount: 5, entryCount: 9, invalidReferences: [], errors: [], ...overrides }
}

function mountBar() {
  return mount(ConstraintTemplateBar, {
    props: {
      semesterId: 2,
      classes: [
        { id: 77, name: '高一1班（新）' },
        { id: 78, name: '高一2班（新）' },
      ],
      reload: vi.fn(async () => {}),
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

/** The template select is the first `el-select` on the bar, the rebinding ones follow. */
function rebindSelect(wrapper: ReturnType<typeof mountBar>, index: number) {
  return wrapper.findAllComponents(ElSelect).at(index + 1)
}

async function runApply(wrapper: ReturnType<typeof mountBar>) {
  ;(wrapper.vm as unknown as { selectedId: number | null }).selectedId = 4
  await flushPromises()
  await (wrapper.vm as unknown as { onApply: () => Promise<void> }).onApply()
  await flushPromises()
}

describe('ConstraintTemplateBar', () => {
  beforeEach(() => {
    httpMock.get.mockReset()
    httpMock.post.mockReset()
    httpMock.delete.mockReset()
    httpMock.get.mockResolvedValue({ data: TEMPLATES })
  })

  it('模板区给出保存与套用入口，并在下拉里标注模板规模', async () => {
    const wrapper = mountBar()
    await flushPromises()

    expect(httpMock.get).toHaveBeenCalledWith('/constraint-templates')
    expect(wrapper.get('[data-testid="constraint-template-save"]').text()).toContain('存为模板')
    expect(wrapper.get('[data-testid="constraint-template-apply"]').text()).toContain('套用模板')
    expect(wrapper.get('[data-testid="constraint-template-delete"]').text()).toContain('删除模板')
    expect(
      (wrapper.vm as unknown as { templates: TemplateItem[] }).templates[0],
    ).toEqual(TEMPLATES[0])
  })

  it('跨学期失效引用：列出哪条因哪个对象失效，重新绑定班级后套用并显示结果', async () => {
    httpMock.post.mockImplementation((url: string) => {
      if (url.endsWith('/preview')) {
        return Promise.resolve({ data: preview({ invalidReferences: [issue()] }) })
      }
      return Promise.resolve({
        data: [
          {
            type: 'CLASS_MAX_PER_DAY',
            params: {},
            weight: 80,
            hard: false,
            enabled: true,
            entries: [
              { id: 1, params: { maxPerDay: 8 } },
              { id: 2, params: { classId: 77, maxPerDay: 5 } },
            ],
          },
          {
            type: 'TEACHER_UNAVAILABLE',
            params: {},
            weight: 100,
            hard: true,
            enabled: true,
            entries: [
              { id: 3, params: { teacherId: 8, dayOfWeek: 1, periodNo: 1 } },
              { id: 4, params: { teacherId: 8, dayOfWeek: 1, periodNo: 2 } },
            ],
          },
        ],
      })
    })

    const wrapper = mountBar()
    await flushPromises()
    await runApply(wrapper)

    // The dry run came first and nothing was written.
    expect(httpMock.post).toHaveBeenCalledWith(
      '/constraint-templates/4/preview',
      { classMapping: {} },
      { params: { semesterId: 2 } },
    )
    expect(httpMock.post).toHaveBeenCalledTimes(1)

    const list = wrapper.get('[data-testid="constraint-template-issues"]')
    expect(list.text()).toContain('班级一天最多 N 节（CLASS_MAX_PER_DAY）')
    expect(list.text()).toContain('第 2 条')
    expect(list.text()).toContain('高一1班（#31）')
    expect(list.text()).toContain('行政班属于其他学期')
    expect(
      wrapper.get('[data-testid="constraint-template-issues-apply"]').attributes('disabled'),
    ).toBeDefined()

    rebindSelect(wrapper, 0)!.vm.$emit('update:modelValue', 77)
    await flushPromises()
    const applyRow = wrapper.get('[data-testid="constraint-template-issues-apply"]')
    expect(applyRow.attributes('disabled')).toBeUndefined()
    await applyRow.trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/constraint-templates/4/apply',
      { classMapping: { '31': 77 } },
      { params: { semesterId: 2, baseVersion: 0 } },
    )
    expect(wrapper.get('[data-testid="constraint-template-result"]').text()).toContain(
      '已套用模板「标准学期」：恢复 2 类配置、4 条条目',
    )
  })

  it('教师/课程失效无法兜底：清单说明原因，也不发套用请求', async () => {
    httpMock.post.mockResolvedValue({
      data: preview({
        invalidReferences: [
          issue({ field: 'teacherId', fieldLabel: '教师', name: null, objectId: 8, position: 1 }),
        ],
      }),
    })
    const wrapper = mountBar()
    await flushPromises()
    await runApply(wrapper)

    expect(wrapper.get('[data-testid="constraint-template-issues-summary"]').text()).toContain(
      '1 处对象（教师/课程）已不存在',
    )
    expect(document.body.textContent).toContain('该对象无法通过套用修复')
    expect(
      wrapper.get('[data-testid="constraint-template-issues-apply"]').attributes('disabled'),
    ).toBeDefined()
    expect(httpMock.post).toHaveBeenCalledTimes(1)
  })

  it('预览报出其他阻止原因时显示原因且不进入套用', async () => {
    httpMock.post.mockResolvedValue({
      data: preview({
        errors: ['教师禁排时段(TEACHER_UNAVAILABLE) 第1 条: 星期6第2节不在本学期作息范围内'],
      }),
    })
    const wrapper = mountBar()
    await flushPromises()
    await runApply(wrapper)

    expect(wrapper.get('[data-testid="constraint-template-error"]').text()).toContain(
      '星期6第2节不在本学期作息范围内',
    )
    expect(httpMock.post).toHaveBeenCalledTimes(1)
  })

  it('服务端拒绝套用时保留原配置并展示清单', async () => {
    httpMock.post.mockImplementation((url: string) => {
      if (url.endsWith('/preview')) {
        return Promise.resolve({ data: preview({ invalidReferences: [issue()] }) })
      }
      return Promise.reject({
        response: {
          status: 409,
          data: {
            message: '模板中有 1 处对象引用在当前学期无效',
            invalidReferences: [issue({ position: 3, objectId: 32 })],
          },
        },
      })
    })
    const wrapper = mountBar()
    await flushPromises()
    await runApply(wrapper)
    rebindSelect(wrapper, 0)!.vm.$emit('update:modelValue', 77)
    await flushPromises()
    await wrapper.get('[data-testid="constraint-template-issues-apply"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-template-error"]').text()).toContain(
      '模板中有 1 处对象引用在当前学期无效',
    )
    // No success alert: the current configuration was left as it was.
    expect(wrapper.find('[data-testid="constraint-template-result"]').exists()).toBe(false)
  })
})
