import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import EmptyStateGuide from './EmptyStateGuide.vue'

type GuideProps = {
  title: string
  description?: string
  buttonText?: string
  buttonTo?: string
  helpSlug?: 'foundation' | 'scheduling' | 'substitution' | 'timetable'
  helpAnchor?: string
  helpText?: string
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/semesters', component: { template: '<div />' } },
      { path: '/help/:slug', component: { template: '<div />' } },
    ],
  })
}

async function mountGuide(props: GuideProps) {
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(EmptyStateGuide, {
    props,
    global: { plugins: [router, ElementPlus] },
  })
  return { wrapper, router }
}

describe('EmptyStateGuide', () => {
  it('renders title, description and the shared test id', async () => {
    const { wrapper } = await mountGuide({
      title: '还没有班级数据',
      description: '点击下方按钮创建第一个行政班。',
    })

    expect(wrapper.find('[data-testid="empty-state-guide"]').exists()).toBe(true)
    expect(wrapper.get('h3').text()).toBe('还没有班级数据')
    expect(wrapper.text()).toContain('点击下方按钮创建第一个行政班。')
  })

  it('emits action when no buttonTo is provided', async () => {
    const { wrapper } = await mountGuide({
      title: '还没有教师数据',
      buttonText: '新建教师',
    })

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('navigates with the router when buttonTo is provided', async () => {
    const { wrapper, router } = await mountGuide({
      title: '还没有学期',
      buttonText: '去创建学期',
      buttonTo: '/admin/semesters',
    })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/admin/semesters')
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('links to the help document with slug and anchor', async () => {
    const { wrapper } = await mountGuide({
      title: '还没有年级数据',
      helpSlug: 'foundation',
      helpAnchor: 'grades',
    })

    const link = wrapper.get('a')
    expect(link.attributes('href')).toBe('/help/foundation#grades')
    expect(link.text()).toBe('查看帮助文档')
  })

  it('supports custom help text and omits the link without a slug', async () => {
    const { wrapper } = await mountGuide({
      title: '还没有课程数据',
      helpSlug: 'foundation',
      helpAnchor: 'courses',
      helpText: '查看课程帮助',
    })
    expect(wrapper.get('a').text()).toBe('查看课程帮助')

    const { wrapper: withoutHelp } = await mountGuide({ title: '暂无数据' })
    expect(withoutHelp.find('a').exists()).toBe(false)
  })

  it('renders without a button when neither buttonText nor buttonTo is given', async () => {
    const { wrapper } = await mountGuide({ title: '暂无调代记录' })
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
