import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConstraintCapacityNotice from './ConstraintCapacityNotice.vue'
import { http } from '@/api/http'
vi.mock('@/api/http', () => ({ http: { post: vi.fn() } }))
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

describe('capacity preview', () => {
  it('discards an older semester response and displays the current shortage', async () => {
    vi.useFakeTimers()
    let oldResponse!: (value: unknown) => void
    vi.mocked(http.post).mockImplementationOnce(() => new Promise((resolve) => { oldResponse = resolve }))
      .mockResolvedValueOnce({ data: { warnings: ['高一2班：需要39，可用38，还差1'] } })
    const wrapper = mount(ConstraintCapacityNotice, { props: { semesterId: 1, configs: [], incomplete: false } })
    await vi.advanceTimersByTimeAsync(350)
    await wrapper.setProps({ semesterId: 2 })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(wrapper.text()).toContain('高一2班：需要39，可用38，还差1')
    oldResponse({ data: { warnings: ['过期班级提示'] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('过期班级提示')
    await wrapper.setProps({ incomplete: true })
    await vi.advanceTimersByTimeAsync(350)
    expect(wrapper.find('[data-testid="constraint-capacity-warning"]').exists()).toBe(false)
    expect(http.post).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
