import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotFoundView from './NotFoundView.vue'

describe('NotFoundView', () => {
  it('renders 404 message and navigates back to workbench home', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>home</div>' } },
        { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
      ],
    })
    await router.push('/missing-page')
    await router.isReady()

    const wrapper = mount(NotFoundView, { global: { plugins: [router, ElementPlus] } })
    expect(wrapper.text()).toContain('404')
    expect(wrapper.text()).toContain('页面不存在')

    await wrapper.get('[data-testid="not-found-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })
})
